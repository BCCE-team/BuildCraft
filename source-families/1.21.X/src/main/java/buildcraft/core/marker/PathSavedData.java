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

public class PathSavedData extends MarkerSavedData<PathSubCache, PathConnection> {
    public static final String NAME = "buildcraft_marker_path";
    public static final Codec<PathSavedData> CODEC = CompoundTag.CODEC.xmap(PathSavedData::new, PathSavedData::saveToTag);
    public static final SavedDataType<PathSavedData> TYPE = new SavedDataType<>(NAME, PathSavedData::new, CODEC, null);

    public PathSavedData(CompoundTag tag, String name) {
        super(tag, name);
    }

    public PathSavedData(CompoundTag tag) {
        super(tag, NAME);
    }

    public PathSavedData() {
        super(NAME);
    }

    public void loadInto(PathSubCache subCache) {
        setCache(subCache);
        for (BlockPos p : markerPositions) {
            subCache.loadMarker(p, null);
        }
        for (List<BlockPos> list : markerConnections) {
            subCache.addConnection(new PathConnection(subCache, list));
        }
    }
}
