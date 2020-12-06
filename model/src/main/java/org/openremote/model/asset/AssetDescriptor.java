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
package org.openremote.model.asset;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.openremote.model.value.NameHolder;

/**
 * Describes an {@link Asset} that can be added to this instance; the {@link #getName()} must match the {@link Asset#type}
 * with which it is associated. For a given {@link Asset} class only one {@link AssetDescriptor} is allowed, each concrete
 * {@link Asset} class must have a corresponding {@link AssetDescriptor} and {@link AssetDescriptor}s are not allowed for
 * abstract {@link Asset} classes.
 * <p>
 * {@link AssetDescriptor#getName} must be globally unique within the context of the manager it is registered with.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "descriptorType")
@JsonTypeName("asset")
public class AssetDescriptor<T extends Asset<?>> implements NameHolder {

    protected String name;
    protected Class<T> type;
    protected String icon;
    protected String colour;

    /**
     * Construct an instance using the {@link Class#getSimpleName} value of the specified type as the descriptor name,
     * the {@link Class#getSimpleName} must therefore be unique enough to not clash with other {@link AssetDescriptor}s.
     */
    public AssetDescriptor(String icon, String colour, Class<T> type) {
        this(icon, colour, type.getSimpleName(), type);
    }

    public AssetDescriptor(String icon, String colour, String name, Class<T> type) {
        this.name = name;
        this.icon = icon;
        this.colour = colour;
        this.type = type;
    }

    /**
     * The unique name of this descriptor and corresponds to the simple class name of {@link #getType()}.
     */
    @Override
    public String getName() {
        return name;
    }

    public Class<T> getType() {
        return type;
    }

    public String getIcon() {
        return icon;
    }

    public String getColour() {
        return colour;
    }
}
