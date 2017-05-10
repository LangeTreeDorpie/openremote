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
import org.openremote.agent.protocol.simulator.element.*;
import org.openremote.model.AbstractValueHolder;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.value.Value;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.openremote.model.Constants.PROTOCOL_NAMESPACE;
import static org.openremote.model.asset.AssetMeta.RANGE_MAX;
import static org.openremote.model.asset.AssetMeta.RANGE_MIN;

public class SimulatorProtocol extends AbstractProtocol {

    /**
     * Controls how and when sensor update is called after an actuator write.
     */
    public enum Mode {
        // Send to actuator values are written through to sensor immediately
        WRITE_THROUGH_IMMEDIATE,

        // Send to actuator values are written through to sensor after configured delay
        WRITE_THROUGH_DELAYED,

        // Producer of send to actuator will have to manually update the sensor by calling updateSensor
        MANUAL
    }

    /**
     * Stores protocol config parameters.
     */
    public static class Instance {
        protected Mode mode;
        protected int delayMilliseconds;
        protected boolean enabled;

        public Instance(Mode mode, int delayMilliseconds, boolean enabled) {
            this.enabled = enabled;
            this.mode = mode;
            this.delayMilliseconds = delayMilliseconds;
        }

        public Mode getMode() {
            return mode;
        }

        public int getDelayMilliseconds() {
            return delayMilliseconds;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    protected static final Map<AttributeRef, SimulatorElement> elements = new HashMap<>();

    private static final Logger LOG = Logger.getLogger(SimulatorProtocol.class.getName());

    public static final int DEFAULT_WRITE_DELAY = 1000;

    public static final String PROTOCOL_NAME = PROTOCOL_NAMESPACE + ":simulator";

    /**
     * Required meta item, the simulator element that should be used, see subclasses of {@link SimulatorElement}.
     */
    public static final String SIMULATOR_ELEMENT = PROTOCOL_NAME + ":element";

    /**
     * Optional (defaults to {@link Mode#WRITE_THROUGH_IMMEDIATE}) determines how sensor updates occur after actuator write.
     */
    public static final String CONFIG_MODE = PROTOCOL_NAME + ":mode";

    /**
     * Optional (defaults to {@link #DEFAULT_WRITE_DELAY}) used in {@link Mode#WRITE_THROUGH_DELAYED} mode to control
     * delay between actuator write and sensor update
     */
    public static final String CONFIG_WRITE_DELAY_MILLISECONDS = PROTOCOL_NAME + ":delayMilliseconds";

    static final protected Map<AttributeRef, Instance> instances = new HashMap<>();

    static final protected Map<AttributeRef, AttributeRef> attributeInstanceMap = new HashMap<>();

    @Override
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    @Override
    protected void doLinkProtocolConfiguration(AssetAttribute protocolConfiguration) {
        AttributeRef protocolRef = protocolConfiguration.getReferenceOrThrow();

        synchronized (instances) {
            instances
                .computeIfAbsent(
                    protocolRef,
                    ref -> {
                        Mode mode = protocolConfiguration
                            .getMetaItem(CONFIG_MODE)
                            .map(item ->
                                item.getValueAsString()
                                    .map(value -> {
                                        try {
                                            return Mode.valueOf(value);
                                        } catch (Exception e) {
                                            LOG.fine("Invalid Mode value '" + item + "' provided");
                                            return null;
                                        }
                                    })
                                    .orElse(null)
                            )
                            .orElse(Mode.WRITE_THROUGH_IMMEDIATE);

                        int writeDelay = protocolConfiguration.getMetaItem(CONFIG_WRITE_DELAY_MILLISECONDS)
                            .flatMap(AbstractValueHolder::getValueAsInteger)
                            .orElse(DEFAULT_WRITE_DELAY);

                        updateDeploymentStatus(protocolRef, protocolConfiguration.isEnabled() ? DeploymentStatus.LINKED_ENABLED : DeploymentStatus.LINKED_DISABLED);
                        return new Instance(mode, writeDelay, protocolConfiguration.isEnabled());
                    }
                );
        }
    }

    @Override
    protected void doUnlinkProtocolConfiguration(AssetAttribute protocolConfiguration) {
        AttributeRef configRef = protocolConfiguration.getReferenceOrThrow();

        synchronized (instances) {
            instances.remove(configRef);
        }
    }

    @Override
    protected void doLinkAttribute(AssetAttribute attribute, AssetAttribute protocolConfiguration) {

        // Get element type from the attribute meta
        String elementType = getElementType(attribute)
            .orElseThrow(
                () -> new IllegalArgumentException(
                    "Can't configure simulator, missing " + SIMULATOR_ELEMENT + " meta item on: " + attribute
                )
            );

        AttributeRef configRef = protocolConfiguration.getReferenceOrThrow();
        AttributeRef attributeRef = attribute.getReferenceOrThrow();

        SimulatorElement element = createElement(elementType, attribute);
        if (attribute.getValue().isPresent()) {
            try {
                element.setState(attribute.getValue().get());
            } catch (IllegalArgumentException ex) {
                throw new RuntimeException("Error setting initial state of: " + attribute, ex);
            }
        }

        LOG.info("Putting element '" + element + "' for: " + attribute);

        synchronized (elements) {
            elements.put(attributeRef, element);
        }

        synchronized (attributeInstanceMap) {
            attributeInstanceMap.put(attributeRef, configRef);
        }
    }

    @Override
    protected void doUnlinkAttribute(AssetAttribute attribute, AssetAttribute protocolConfiguration) {
        AttributeRef attributeRef = attribute.getReferenceOrThrow();

        synchronized (elements) {
            elements.remove(attributeRef);
        }

        synchronized (attributeInstanceMap) {
            attributeInstanceMap.remove(attributeRef);
        }
    }

    @Override
    protected void processLinkedAttributeWrite(AttributeEvent event, AssetAttribute protocolConfiguration) {
        putState(event.getAttributeState());
    }

    /**
     * Call this to simulate an immediate sensor update (it uses the last value supplied from send to actuator).
     */
    public void updateSensor(String entityId, String attributeName) {
        updateSensor(new AttributeRef(entityId, attributeName));
    }

    /**
     * Call this to simulate a sensor update after the specified delay (it uses the last value supplied from send to actuator).
     */
    public void updateSensor(String entityId, String attributeName, int updateSensorDelayMilliseconds) {
        updateSensor(new AttributeRef(entityId, attributeName), updateSensorDelayMilliseconds);
    }

    /**
     * Call this to simulate an immediate sensor update (it uses the last value supplied from send to actuator).
     */
    public void updateSensor(AttributeRef attributeRef) {
        updateSensor(attributeRef, 0);
    }

    /**
     * Call this to simulate a sensor update after the specified delay (it uses the last value supplied from send to actuator).
     */
    public void updateSensor(AttributeRef attributeRef, int updateSensorDelayMilliseconds) {
        Value value = getState(attributeRef);
        final AttributeState state = new AttributeState(attributeRef, value);
        AttributeRef instanceRef = attributeInstanceMap.get(attributeRef);

        if (instanceRef == null) {
            throw new IllegalArgumentException("Attribute is not referenced by an instance:" + attributeRef);
        }

        Instance instance;

        synchronized (instances) {
            instance = instances.get(instanceRef);
        }

        if (instance == null) {
            throw new IllegalArgumentException("No instance found by name '" + instanceRef + "'");
        }

        if (!instance.isEnabled()) {
            LOG.fine("Simulator protocol configuration is disabled so cannot process request");
            return;
        }

        if (updateSensorDelayMilliseconds <= 0) {
            updateLinkedAttribute(state);
        } else {
            executorService.schedule(() -> updateLinkedAttribute(state), updateSensorDelayMilliseconds);
        }
    }

    /**
     * Call this to simulate a send to actuator.
     */
    protected void putState(String entityId, String attributeName, Value value) {
        putState(new AttributeState(new AttributeRef(entityId, attributeName), value));
    }

    /**
     * Call this to simulate a send to actuator.
     */
    protected void putState(AttributeRef attributeRef, Value value) {
        putState(new AttributeState(attributeRef, value));
    }

    /**
     * Call this to simulate a send to actuator.
     */
    protected void putState(AttributeEvent event) {
        putState(event.getAttributeState());
    }

    /**
     * Call this to simulate a send to actuator.
     */
    protected void putState(AttributeState attributeState) {
        AttributeRef attributeRef = attributeState.getAttributeRef();
        AttributeRef instanceRef = attributeInstanceMap.get(attributeRef);

        if (instanceRef == null) {
            throw new IllegalArgumentException("Attribute is not referenced by an instance:" + attributeRef);
        }

        Instance instance;

        synchronized (instances) {
            instance = instances.get(instanceRef);
        }

        if (instance == null) {
            throw new IllegalArgumentException("No instance found by name '" + instanceRef + "'");
        }

        if (!instance.isEnabled()) {
            LOG.fine("Simulator protocol configuration is disabled so cannot process request");
            return;
        }

        synchronized (elements) {
            LOG.info("Put simulator state: " + attributeState);
            SimulatorElement element = elements.get(attributeRef);
            if (element == null) {
                throw new IllegalArgumentException("No simulated element for: " + attributeRef);
            }

            element.setState(attributeState.getCurrentValue().orElse(null));
        }

        if (instance.getMode() != Mode.MANUAL) {
            updateSensor(attributeRef, instance.getMode() == Mode.WRITE_THROUGH_IMMEDIATE ? 0 : instance.getDelayMilliseconds());
        }
    }

    /**
     * Call this to get the current value of an attribute.
     */
    public Value getState(String entityId, String attributeName) {
        return getState(new AttributeRef(entityId, attributeName));
    }

    /**
     * Call this to get the current value of an attribute.
     */
    public Value getState(AttributeRef attributeRef) {
        synchronized (elements) {
            SimulatorElement element = elements.get(attributeRef);
            if (element == null)
                throw new IllegalArgumentException("No simulated element for: " + attributeRef);
            return element.getState();
        }
    }

    protected SimulatorElement createElement(String elementType, AssetAttribute attribute) {
        switch (elementType.toLowerCase(Locale.ROOT)) {
            case SwitchSimulatorElement.ELEMENT_NAME:
                return new SwitchSimulatorElement();
            case IntegerSimulatorElement.ELEMENT_NAME_INTEGER:
                return new IntegerSimulatorElement();
            case DecimalSimulatorElement.ELEMENT_NAME:
                return new DecimalSimulatorElement();
            case IntegerSimulatorElement.ELEMENT_NAME_RANGE:
                int min = attribute.getMetaItem(RANGE_MIN).flatMap(AbstractValueHolder::getValueAsInteger).orElse(0);
                int max = attribute.getMetaItem(RANGE_MAX).flatMap(AbstractValueHolder::getValueAsInteger).orElse(100);
                return new IntegerSimulatorElement(min, max);
            case ColorSimulatorElement.ELEMENT_NAME:
                return new ColorSimulatorElement();
            default:
                throw new UnsupportedOperationException("Can't simulate element '" + elementType + "': " + attribute);
        }
    }

    public static Optional<String> getElementType(AssetAttribute attribute) {
        return
            attribute.getMetaItem(SIMULATOR_ELEMENT)
                .flatMap(AbstractValueHolder::getValueAsString);
    }
}