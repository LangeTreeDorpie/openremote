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
package org.openremote.model.attribute;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openremote.model.event.shared.AssetInfo;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.v2.AttributeDescriptor;

import java.util.Objects;
import java.util.Optional;

/**
 * A timestamped {@link AttributeState}.
 */
public class AttributeEvent extends SharedEvent implements AssetInfo {

    public static final String HEADER_SOURCE = AttributeEvent.class.getName() + ".SOURCE";

    /**
     * Processing of the attribute event depends on the origin of the event.
     */
    public enum Source {

        /**
         * The event was created by a client, it's a write request by a user.
         */
        CLIENT,

        /**
         * The event was created by internal processing, for example, as a rule
         * consequence. Protocols can also create events for internal processing,
         * to update any assets' state.
         */
        INTERNAL, // This needs to be more fine grained

        /**
         * An attribute event has been created by the linking service, which enables
         * writing the same value onto another, linked attribute.
         */
        ATTRIBUTE_LINKING_SERVICE,

        /**
         * The event is a value change on a sensor, created by a protocol.
         */
        SENSOR,

        /**
         * An attribute event generated by a gateway connector.
         */
        GATEWAY
    }

    protected AttributeState attributeState;
    protected String realm;
    protected String parentId;

    public <T> AttributeEvent(String assetId, AttributeDescriptor<T> attributeDescriptor, T value) {
        this(assetId, attributeDescriptor.getName(), value);
    }

    public AttributeEvent(String assetId, String attributeName, Object value) {
        this(new AttributeState(new AttributeRef(assetId, attributeName), value));
    }

    public AttributeEvent(String assetId, String attributeName) {
        this(new AttributeState(new AttributeRef(assetId, attributeName)));
    }

    public AttributeEvent(String assetId, String attributeName, boolean deleted) {
        this(new AttributeState(new AttributeRef(assetId, attributeName)));
        attributeState.deleted = deleted;
    }

    public AttributeEvent(String assetId, String attributeName, Object value, long timestamp) {
        this(new AttributeState(new AttributeRef(assetId, attributeName), value), timestamp);
    }

    public AttributeEvent(AttributeRef attributeRef, Object value) {
        this(new AttributeState(attributeRef, value));
    }

    public AttributeEvent(AttributeRef attributeRef) {
        this(new AttributeState(attributeRef));
    }

    public AttributeEvent(AttributeRef attributeRef, Object value, long timestamp) {
        this(new AttributeState(attributeRef, value), timestamp);
    }

    public AttributeEvent(AttributeState attributeState) {
        this.attributeState = attributeState;
    }

    @JsonCreator
    public AttributeEvent(@JsonProperty("state") AttributeState attributeState, @JsonProperty("t") long timestamp) {
        super(timestamp);
        Objects.requireNonNull(attributeState);
        this.attributeState = attributeState;
    }

    public AttributeState getAttributeState() {
        return attributeState;
    }

    public AttributeRef getAttributeRef() {
        return getAttributeState().getAttributeRef();
    }

    public String getAssetId() {
        return getAttributeRef().getAssetId();
    }

    public String getRealm() {
        return realm;
    }

    public AttributeEvent setRealm(String realm) {
        this.realm = realm;
        return this;
    }

    @Override
    public String getParentId() {
        return parentId;
    }

    public AttributeEvent setParentId(String parentId) {
        this.parentId = parentId;
        return this;
    }

    @Override
    public String[] getAttributeNames() {
        return new String[]{getAttributeRef().getAttributeName()};
    }

    public String getAttributeName() {
        return getAttributeRef().getAttributeName();
    }

    public Optional<Object> getValue() {
        return getAttributeState().getValue();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            "timestamp=" + timestamp +
            ", attributeState=" + attributeState +
            "}";
    }
}
