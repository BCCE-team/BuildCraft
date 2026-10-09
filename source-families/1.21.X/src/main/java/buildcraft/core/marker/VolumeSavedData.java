//? source if >=1.21.11
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.marker;

import java.util.List;

import com.mojang.serialization.Codec;

import buildcraft.lib.marker.MarkerSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedDataType;

public class VolumeSavedData extends MarkerSavedData<VolumeSubCache, VolumeConnection> {
    public static final String NAME = "buildcraft_marker_volume";
    public static final Codec<VolumeSavedData> CODEC = CompoundTag.CODEC.xmap(VolumeSavedData::new, VolumeSavedData::saveToTag);
    public static final SavedDataType<VolumeSavedData> TYPE = new SavedDataType<>(NAME, VolumeSavedData::new, CODEC, null);

    public VolumeSavedData(CompoundTag tag, String name) {
        super(tag, name);
    }

    public VolumeSavedData(CompoundTag tag) {
        super(tag, NAME);
    }

    public VolumeSavedData() {
        super(NAME);
    }

    public void loadInto(VolumeSubCache subCache) {
        setCache(subCache);
        for (BlockPos p : markerPositions) {
            subCache.loadMarker(p, null);
        }
        for (List<BlockPos> list : markerConnections) {
            subCache.addConnection(new VolumeConnection(subCache, list));
        }
    }
}
