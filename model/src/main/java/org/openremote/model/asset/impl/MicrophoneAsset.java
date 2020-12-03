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
package org.openremote.model.asset.impl;

import org.openremote.model.Constants;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

import javax.persistence.Entity;
import java.util.Optional;

@Entity
public class MicrophoneAsset extends Asset<MicrophoneAsset> {

    public static final AttributeDescriptor<Double> SOUND_LEVEL = new AttributeDescriptor<>("soundLevel", ValueType.POSITIVE_NUMBER,
        new MetaItem<>(MetaItemType.UNIT_TYPE, Constants.UNITS_SOUND_DECIBELS)
    );

    public static final AssetDescriptor<MicrophoneAsset> DESCRIPTOR = new AssetDescriptor<>("microphone", "47A5FF", MicrophoneAsset.class);

    /**
     * For use by hydrators (i.e. JPA/Jackson)
     */
    MicrophoneAsset() {
        this(null);
    }

    protected MicrophoneAsset(String name, AssetDescriptor<? extends MicrophoneAsset> descriptor) {
        super(name, descriptor);
    }

    public MicrophoneAsset(String name) {
        super(name, DESCRIPTOR);
    }

    public Optional<Double> getSoundLevel() {
        return getAttributes().getValue(SOUND_LEVEL);
    }
}
