/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.marker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import buildcraft.lib.tile.TileMarker;
//? if neoforge && (>=26.2) {
import buildcraft.lib.compat.minecraft.text.BCTextFormat;
//? } else {
import net.minecraft.ChatFormatting;
//?}
import net.minecraft.core.BlockPos;

public abstract class MarkerConnection<C extends MarkerConnection<C>> {
    public final MarkerSubCache<C> subCache;

    public MarkerConnection(MarkerSubCache<C> subCache) {
        this.subCache = subCache;
    }

    /** Removes the specified marker from this connection. This should be called via
     * {@link MarkerSubCache#removeMarker(BlockPos)}. This may need to remove itself and split itself up (if the resulting
     * connection is invalid). */
    public abstract void removeMarker(BlockPos pos);

    public abstract Collection<BlockPos> getMarkerPositions();
    public abstract void renderInWorld(PoseStack pose, Matrix4f matrix);

    public void getDebugInfo(BlockPos caller, List<String> left) {
        Collection<BlockPos> positions = getMarkerPositions();
        List<BlockPos> list = new ArrayList<>(positions);
        if (positions instanceof Set) {
            Collections.sort(list);
        }
        for (BlockPos pos : list) {
            TileMarker<C> marker = subCache.getMarker(pos);
            String s = "  " + pos + " [";
            if (marker == null) {
//? if neoforge && (>=26.2) {
                s += BCTextFormat.RED + "U";
//? } else {
                s += ChatFormatting.RED + "U";
//?}
            } else {
//? if neoforge && (>=26.2) {
                s += BCTextFormat.GREEN + "L";
//? } else {
                s += ChatFormatting.GREEN + "L";
//?}
            }
            if (pos.equals(caller)) {
//? if neoforge && (>=26.2) {
                s += BCTextFormat.BLACK + "S";
//? } else {
                s += ChatFormatting.BLACK + "S";
//?}
            } else {
//? if neoforge && (>=26.2) {
                s += BCTextFormat.AQUA + "C";
//? } else {
                s += ChatFormatting.AQUA + "C";
//?}
            }
            s += getTypeInfo(pos, marker);
//? if neoforge && (>=26.2) {
            s += BCTextFormat.RESET + "]";
//? } else {
            s += ChatFormatting.RESET + "]";
//?}
            left.add(s);
        }
    }

    protected String getTypeInfo(BlockPos pos, @Nullable TileMarker<C> value) {
        return "";
    }
}
