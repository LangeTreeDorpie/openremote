/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.agent.protocol.websocket;

import io.netty.channel.ChannelHandler;
import org.apache.http.HttpHeaders;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.util.BasicAuthHelper;
import org.openremote.agent.protocol.io.AbstractIoClientProtocol;
import org.openremote.container.web.WebTargetBuilder;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeExecuteStatus;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.auth.UsernamePassword;
import org.openremote.model.protocol.ProtocolUtil;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import org.openremote.model.util.TextUtil;
import org.openremote.model.value.ValueType;
import org.openremote.model.value.Values;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.openremote.agent.protocol.http.HttpClientProtocol.DEFAULT_CONTENT_TYPE;
import static org.openremote.agent.protocol.http.HttpClientProtocol.DEFAULT_HTTP_METHOD;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

/**
 * This is a generic {@link org.openremote.model.asset.agent.Protocol} for communicating with a Websocket server
 * using {@link String} based messages.
 * <p>
 * <h2>Protocol Specifics</h2>
 * When the websocket connection is established it is possible to subscribe to events by specifying the
 * {@link WebsocketClientAgent#CONNECT_SUBSCRIPTIONS} on the {@link WebsocketClientAgent} or
 * {@link WebsocketClientAgent.WebsocketClientAgentLink#getConnectSubscriptions()} on linked {@link Attribute}s; a
 * subscription can be a message sent over the websocket or a HTTP REST API call.
 */
public class WebsocketClientProtocol extends AbstractIoClientProtocol<WebsocketClientProtocol, WebsocketClientAgent, String, WebsocketIoClient<String>, WebsocketClientAgent.WebsocketClientAgentLink> {

    public static final String PROTOCOL_DISPLAY_NAME = "Websocket Client";
    private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, WebsocketClientProtocol.class);
    public static final int CONNECTED_SEND_DELAY_MILLIS = 2000;
    protected ResteasyClient resteasyClient;
    protected List<Runnable> protocolConnectedTasks;
    protected Map<AttributeRef, Runnable> attributeConnectedTasks;
    protected MultivaluedMap<String, String> clientHeaders;
    protected final List<Pair<AttributeRef, Consumer<String>>> protocolMessageConsumers = new ArrayList<>();

    public WebsocketClientProtocol(WebsocketClientAgent agent) {
        super(agent);
    }

    @Override
    public String getProtocolName() {
        return PROTOCOL_DISPLAY_NAME;
    }

    @Override
    protected void doStop(Container container) throws Exception {
        super.doStop(container);

        clientHeaders = null;
        protocolConnectedTasks = null;
        attributeConnectedTasks = null;
        protocolMessageConsumers.clear();
    }

    @Override
    protected Supplier<ChannelHandler[]> getEncoderDecoderProvider() {
        return getGenericStringEncodersAndDecoders(client.ioClient, agent);
    }

    @Override
    protected void onMessageReceived(String message) {
        protocolMessageConsumers.forEach(c -> {
            if (c.value != null) {
                c.value.accept(message);
            }
        });
    }

    @Override
    protected String createWriteMessage(Attribute<?> attribute, WebsocketClientAgent.WebsocketClientAgentLink agentLink, AttributeEvent event, Object processedValue) {

        if (attribute.getValueType().equals(ValueType.EXECUTION_STATUS)) {
            boolean isRequestStart = event.getValue()
                .flatMap(v -> Values.getValue(v, AttributeExecuteStatus.class))
                .map(status -> status == AttributeExecuteStatus.REQUEST_START)
                .orElse(false);
            if (!isRequestStart) {
                LOG.fine("Unsupported execution status: " + event);
                return null;
            }
        }

        return processedValue != null ? processedValue.toString() : null;
    }

    @Override
    protected WebsocketIoClient<String> doCreateIoClient() throws Exception {

        String uriStr = agent.getConnectUri().orElseThrow(() ->
            new IllegalArgumentException("Missing or invalid connectUri: " + agent));

        URI uri = new URI(uriStr);

        /* We're going to fail hard and fast if optional meta items are incorrectly configured */

        Optional<OAuthGrant> oAuthGrant = agent.getOAuthGrant();
        Optional<UsernamePassword> usernameAndPassword = agent.getUsernamePassword();
        Optional<ValueType.MultivaluedStringMap> headers = agent.getConnectHeaders();
        Optional<WebsocketSubscription[]> subscriptions = agent.getConnectSubscriptions();

        if (!oAuthGrant.isPresent() && usernameAndPassword.isPresent()) {
            String authValue = BasicAuthHelper.createHeader(usernameAndPassword.get().getUsername(), usernameAndPassword.get().getPassword());
            headers = Optional.of(headers.map(h -> {
                h.remove(HttpHeaders.AUTHORIZATION);
                h.replace(HttpHeaders.AUTHORIZATION, Collections.singletonList(authValue));
                return h;
            }).orElseGet(() -> {
                ValueType.MultivaluedStringMap h = new ValueType.MultivaluedStringMap();
                h.add(HttpHeaders.AUTHORIZATION, authValue);
                return h;
            }));
        }

        clientHeaders = headers.orElse(null);
        WebsocketIoClient<String> websocketClient = new WebsocketIoClient<>(uri, headers.orElse(null), oAuthGrant.orElse(null), executorService);
        MultivaluedMap<String, String> finalHeaders = headers.orElse(null);

        subscriptions.ifPresent(websocketSubscriptions ->
            addProtocolConnectedTask(() -> doSubscriptions(finalHeaders, websocketSubscriptions))
        );

        return websocketClient;
    }

    @Override
    protected void setConnectionStatus(ConnectionStatus connectionStatus) {
        super.setConnectionStatus(connectionStatus);
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            onConnected();
        }
    }

    @Override
    protected void doLinkAttribute(String assetId, Attribute<?> attribute, WebsocketClientAgent.WebsocketClientAgentLink agentLink) {
        @SuppressWarnings("unchecked")
        Optional<WebsocketSubscription[]> subscriptions = agentLink.getWebsocketSubscriptions();
        AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());

        subscriptions.ifPresent(websocketSubscriptions -> {
            Runnable task = () -> doSubscriptions(clientHeaders, websocketSubscriptions);
            addAttributeConnectedTask(attributeRef, task);
            if (client.ioClient.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                executorService.schedule(task, 1000);
            }
        });

        Consumer<String> messageConsumer = ProtocolUtil.createGenericAttributeMessageConsumer(assetId, attribute, agent.getAgentLink(attribute), timerService::getCurrentTimeMillis, this::updateLinkedAttribute);

        if (messageConsumer != null) {
            protocolMessageConsumers.add(new Pair<>(attributeRef, messageConsumer));
        }
    }

    @Override
    protected void doUnlinkAttribute(String assetId, Attribute<?> attribute, WebsocketClientAgent.WebsocketClientAgentLink agentLink) {
        AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
        protocolMessageConsumers.removeIf((attrRefConsumer) -> attrRefConsumer.key.equals(attributeRef));
        attributeConnectedTasks.remove(attributeRef);
    }

    protected void onConnected() {
        // Look for any subscriptions that need to be processed
        if (protocolConnectedTasks != null) {
            // Execute after a delay to ensure connection is properly initialised
            executorService.schedule(() -> protocolConnectedTasks.forEach(Runnable::run), CONNECTED_SEND_DELAY_MILLIS);
        }

        if (attributeConnectedTasks != null) {
            // Execute after a delay to ensure connection is properly initialised
            executorService.schedule(() -> attributeConnectedTasks.forEach((ref, task) -> task.run()), CONNECTED_SEND_DELAY_MILLIS);
        }
    }

    protected void addProtocolConnectedTask(Runnable task) {
        if (protocolConnectedTasks == null) {
            protocolConnectedTasks = new ArrayList<>();
        }
        protocolConnectedTasks.add(task);
    }

    protected void addAttributeConnectedTask(AttributeRef attributeRef, Runnable task) {
        if (attributeConnectedTasks == null) {
            attributeConnectedTasks = new HashMap<>();
        }

        attributeConnectedTasks.put(attributeRef, task);
    }

    protected void doSubscriptions(MultivaluedMap<String, String> headers, WebsocketSubscription[] subscriptions) {
        LOG.info("Executing subscriptions for websocket: " + client.ioClient.getClientUri());

        // Inject OAuth header
        if (!TextUtil.isNullOrEmpty(client.ioClient.authHeaderValue)) {
            if (headers == null) {
                headers = new MultivaluedHashMap<>();
            }
            headers.remove(HttpHeaders.AUTHORIZATION);
            headers.add(HttpHeaders.AUTHORIZATION, client.ioClient.authHeaderValue);
        }

        MultivaluedMap<String, String> finalHeaders = headers;
        Arrays.stream(subscriptions).forEach(
            subscription -> doSubscription(finalHeaders, subscription)
        );
    }

    protected void doSubscription(MultivaluedMap<String, String> headers, WebsocketSubscription subscription) {
        if (subscription instanceof WebsocketHttpSubscription) {
            WebsocketHttpSubscription httpSubscription = (WebsocketHttpSubscription)subscription;

            if (TextUtil.isNullOrEmpty(httpSubscription.uri)) {
                LOG.warning("Websocket subscription missing or empty URI so skipping: " + subscription);
                return;
            }

            URI uri;

            try {
                uri = new URI(httpSubscription.uri);
            } catch (URISyntaxException e) {
                LOG.warning("Websocket subscription invalid URI so skipping: " + subscription);
                return;
            }

            if (httpSubscription.method == null) {
                httpSubscription.method = WebsocketHttpSubscription.Method.valueOf(DEFAULT_HTTP_METHOD);
            }

            if (TextUtil.isNullOrEmpty(httpSubscription.contentType)) {
                httpSubscription.contentType = DEFAULT_CONTENT_TYPE;
            }

            if (httpSubscription.headers != null) {
                headers = headers != null ? new MultivaluedHashMap<String, String>(headers) : new MultivaluedHashMap<>();
                MultivaluedMap<String, String> finalHeaders = headers;
                httpSubscription.headers.forEach((header, values) -> {
                    if (values == null || values.isEmpty()) {
                        finalHeaders.remove(header);
                    } else {
                        finalHeaders.addAll(header, values);
                    }
                });
            }

            WebTargetBuilder webTargetBuilder = new WebTargetBuilder(resteasyClient, uri);

            if (headers != null) {
                webTargetBuilder.setInjectHeaders(headers);
            }

            LOG.fine("Creating web target client for subscription '" + uri + "'");
            ResteasyWebTarget target = webTargetBuilder.build();

            Invocation invocation;

            if (httpSubscription.body == null) {
                invocation = target.request().build(httpSubscription.method.toString());
            } else {
                invocation = target.request().build(httpSubscription.method.toString(), Entity.entity(httpSubscription.body, httpSubscription.contentType));
            }
            Response response = invocation.invoke();
            response.close();
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                LOG.warning("WebsocketHttpSubscription returned an un-successful response code: " + response.getStatus());
            }
        } else {
            Values.asJSON(subscription.body).ifPresent(jsonString -> client.ioClient.sendMessage(jsonString));
        }
    }
}
