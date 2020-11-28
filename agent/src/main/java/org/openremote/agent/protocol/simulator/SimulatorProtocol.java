/*
 * Copyright 2016, OpenRemote Inc.
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
package org.openremote.agent.protocol.simulator;

import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.simulator.SimulatorAttributeInfo;
import org.openremote.model.simulator.SimulatorReplayDatapoint;
import org.openremote.model.simulator.SimulatorState;
import org.openremote.model.syslog.SyslogCategory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openremote.container.concurrent.GlobalLock.withLock;
import static org.openremote.container.concurrent.GlobalLock.withLockReturning;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

public class SimulatorProtocol extends AbstractProtocol<SimulatorAgent, SimulatorAgent.SimulatorAgentLink> {

    private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, SimulatorProtocol.class);
    public static final String PROTOCOL_DISPLAY_NAME = "Simulator";

    protected final Map<AttributeRef, ScheduledFuture<?>> replayMap = new HashMap<>();

    public SimulatorProtocol(SimulatorAgent agent) {
        super(agent);
    }

    @Override
    public String getProtocolName() {
        return PROTOCOL_DISPLAY_NAME;
    }

    @Override
    public String getProtocolInstanceUri() {
        return "simulator://" + agent.getId();
    }

    @Override
    protected void doStart(Container container) throws Exception {
        setConnectionStatus(ConnectionStatus.CONNECTED);
    }

    @Override
    protected void doStop(Container container) throws Exception {
    }

    @Override
    protected void doLinkAttribute(String assetId, Attribute<?> attribute, SimulatorAgent.SimulatorAgentLink agentLink) {

        // Look for replay data
        agentLink.getSimulatorReplayData()
            .ifPresent(simulatorReplayDatapoints -> {
                LOG.info("Simulator replay data found for linked attribute: " + attribute);
                AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
                ScheduledFuture<?> updateValueFuture = scheduleReplay(attributeRef, simulatorReplayDatapoints);
                if (updateValueFuture != null) {
                    replayMap.put(attributeRef, updateValueFuture);
                } else {
                    LOG.warning("Failed to schedule replay update value for simulator replay attribute: " + attribute);
                    replayMap.put(attributeRef, null);
                }
            });
    }

    @Override
    protected void doUnlinkAttribute(String assetId, Attribute<?> attribute, SimulatorAgent.SimulatorAgentLink agentLink) {
        AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
        ScheduledFuture<?> updateValueFuture = replayMap.remove(attributeRef);

        if (updateValueFuture != null) {
            updateValueFuture.cancel(true);
        }
    }

    @Override
    protected void doLinkedAttributeWrite(Attribute<?> attribute, SimulatorAgent.SimulatorAgentLink agentLink, AttributeEvent event, Object processedValue) {
        if (replayMap.containsKey(event.getAttributeRef())) {
            LOG.info("Attempt to write to linked attribute that is configured for value replay so ignoring: " + attribute);
            return;
        }

        // Assume write through and endpoint returned the value we sent
        LOG.fine("Write to linked attribute so simulating that the endpoint returned the written value");
        updateSensor(event.getAttributeRef(), processedValue);
    }

    /**
     * Call this to simulate a sensor update
     */
    public void updateSensor(AttributeRef attributeRef, Object value) {
        updateSensor(attributeRef, value, 0L);
    }

    /**
     * Call this to simulate a sensor update using the specified timestamp
     */
    public void updateSensor(AttributeRef attributeRef, Object value, long timestamp) {
        withLock(getProtocolName() + "::updateSensor", () -> {

            AttributeState state = new AttributeState(attributeRef, value);
            executorService.submit(() -> {
                Attribute<?> attribute = getLinkedAttributes().get(attributeRef);
                if (attribute == null) {
                    LOG.info("Attempt to update unlinked attribute: " + state);
                    return;
                }
                updateLinkedAttribute(state, timestamp);
            });
        });
    }

    public Map<AttributeRef, ScheduledFuture<?>> getReplayMap() {
        return replayMap;
    }

    protected ScheduledFuture<?> scheduleReplay(AttributeRef attributeRef, SimulatorReplayDatapoint[] simulatorReplayDatapoints) {
        LOG.fine("Scheduling linked attribute replay update");

        long now = LocalDateTime.now().get(ChronoField.SECOND_OF_DAY);

        SimulatorReplayDatapoint nextDatapoint = Arrays.stream(simulatorReplayDatapoints)
            .filter(replaySimulatorDatapoint -> replaySimulatorDatapoint.timestamp > now)
            .findFirst()
            .orElse(simulatorReplayDatapoints[0]);

        if (nextDatapoint == null) {
            LOG.info("Next datapoint not found so replay cancelled: " + attributeRef);
            return null;
        }
        long nextRun = nextDatapoint.timestamp;
        if (nextRun <= now) { //now is after so nextRun is next day
            nextRun += 86400; //day in seconds
        }
        long nextRunRelative = nextRun - now;

        LOG.info("Next update for asset " + attributeRef.getAssetId() + " for attribute " + attributeRef.getAttributeName() + " in " + nextRunRelative + " second(s)");
        return executorService.schedule(() -> {
            withLock(getProtocolName() + "::firingNextUpdate", () -> {
                LOG.info("Updating asset " + attributeRef.getAssetId() + " for attribute " + attributeRef.getAttributeName() + " with value " + nextDatapoint.value.toString());
                try {
                    updateLinkedAttribute(new AttributeState(attributeRef, nextDatapoint.value));
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Exception thrown when updating value: %s", e);
                } finally {
                    replayMap.put(attributeRef, scheduleReplay(attributeRef, simulatorReplayDatapoints));
                }
            });
        }, nextRunRelative, TimeUnit.SECONDS);
    }
}
