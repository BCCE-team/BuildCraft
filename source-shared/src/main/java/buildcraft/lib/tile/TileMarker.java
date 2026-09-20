/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.tile;

import java.util.List;

import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerConnection;
import buildcraft.lib.marker.MarkerSubCache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class TileMarker<C extends MarkerConnection<C>> extends TileBC_Neptune implements IDebuggable {
    private boolean removedFromWorld = false;

    public TileMarker(BlockEntityType<?> p_155228_, BlockPos p_155229_, BlockState p_155230_) {
        super(p_155228_, p_155229_, p_155230_);
    }

    public abstract MarkerCache<? extends MarkerSubCache<C>> getCache();

    public MarkerSubCache<C> getLocalCache() {
        return getCache().getSubCache(level);
    }

    /** @return True if this has lasers being emitted, or any other reason you want. Activates the surrounding "glow"
     *         parts for the block model. */
    public abstract boolean isActiveForRender();

    public C getCurrentConnection() {
        return getLocalCache().getConnection(getBlockPos());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        removedFromWorld = false;
        MarkerSubCache<C> cache = getLocalCache();
        cache.loadMarker(getBlockPos(), this);
        cache.syncMarkerState(getBlockPos());
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (!removedFromWorld) {
            getLocalCache().unloadMarker(getBlockPos());
        }
    }

    @Override
    public void setRemoved() {
        // Vanilla also calls setRemoved() when a chunk unloads. Do not treat that as a broken marker.
        super.setRemoved();
    }

    @Override
    public void onRemove(boolean dropSelf) {
        super.onRemove(dropSelf);
        if (removedFromWorld) {
            return;
        }
        removedFromWorld = true;
        getLocalCache().removeMarker(getBlockPos());
    }

    protected void disconnectFromOthers() {
        C currentConnection = getCurrentConnection();
        if (currentConnection != null) {
            currentConnection.removeMarker(getBlockPos());
        }
    }

    /**
     * Removes a marker as part of a machine claiming its area/path.
     *
     * This intentionally drops the marker item even if the player who placed the machine is in creative mode. Claiming
     * converts the marker-defined area into the machine's saved box/path and returns the reusable marker item; it is not
     * treated as a normal player break.
     */
    protected void removeClaimedMarkerBlock(BlockPos pos) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        ItemStack drop = new ItemStack(state.getBlock().asItem());
        if (!drop.isEmpty()) {
            Block.popResource(level, pos, drop);
        }
        level.destroyBlock(pos, false);
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        C current = getCurrentConnection();
        MarkerSubCache<C> cache = getLocalCache();
        left.add("Exists = " + (cache.getMarker(getBlockPos()) == this));
        if (current == null) {
            left.add("Connection = null");
        } else {
            left.add("Connection:");
            current.getDebugInfo(getBlockPos(), left);
        }
    }
}
