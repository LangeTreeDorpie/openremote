/*
 * Copyright 2020, OpenRemote Inc.
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
package org.openremote.agent.protocol.tradfri;

import org.openremote.model.asset.agent.Agent;
import org.openremote.model.asset.agent.AgentDescriptor;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.asset.agent.Protocol;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.ValueType;

import java.util.Optional;

public class TradfriAgent extends Agent<TradfriAgent, TradfriProtocol, AgentLink.Default> {

    public static final AgentDescriptor<TradfriAgent, TradfriProtocol, AgentLink.Default> DESCRIPTOR = new AgentDescriptor<>(
        TradfriAgent.class, TradfriProtocol.class, AgentLink.Default.class
    );

    /**
     * The security code for the IKEA TRÅDFRI gateway.
     */
    public static final AttributeDescriptor<String> SECURITY_CODE = new AttributeDescriptor<>("securityCode", ValueType.STRING);

    public TradfriAgent(String name) {
        super(name, DESCRIPTOR);
    }

    public Optional<String> getSecurityCode() {
        return getAttributes().getValue(SECURITY_CODE);
    }

    @Override
    public TradfriProtocol getProtocolInstance() {
        return new TradfriProtocol(this);
    }
}
