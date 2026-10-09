/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.blockEntity;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.tiles.ITileAreaProvider;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.marker.VolumeCache;
import buildcraft.core.marker.VolumeConnection;
import buildcraft.lib.marker.MarkerSubCache;
import buildcraft.lib.misc.PositionUtil;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.TileMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileMarkerVolume extends TileMarker<VolumeConnection> implements ITileAreaProvider {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("marker_volume");
    public static final int NET_SIGNALS_ON = IDS.allocId("SIGNALS_ON");
    public static final int NET_SIGNALS_OFF = IDS.allocId("SIGNALS_OFF");

    private boolean showSignals = false;

    public TileMarkerVolume(BlockPos pos, BlockState state) {
    	super(BCCoreBlocks.MARKER_VOLUME_TILE_BC8.get(), pos, state);
        caps.addCapabilityInstance(TilesAPI.CAP_TILE_AREA_PROVIDER, this, EnumPipePart.VALUES);
    }


    @Override
    public void onLoad() {
        super.onLoad();
        refreshSignalStateFromWorld();
    }

    public void refreshSignalStateFromWorld() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean powered = level.hasNeighborSignal(worldPosition);
        if (showSignals != powered) {
            showSignals = powered;
            markChunkDirty();
            sendNetworkUpdate(showSignals ? NET_SIGNALS_ON : NET_SIGNALS_OFF);
        } else {
            sendNetworkUpdate(NET_RENDER_DATA);
        }
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public boolean isShowingSignals() {
        return showSignals;
    }

    @Override
    public VolumeCache getCache() {
        return VolumeCache.INSTANCE;
    }

    @Override
    public boolean isActiveForRender() {
        return showSignals || getCurrentConnection() != null;
    }

    
    @Override
	protected void writeData(BCValueOutput bcData) {
		super.writeData(bcData);
        bcData.writeBoolean("showSignals", showSignals);
	}

	@Override
	protected void readData(BCValueInput bcData) {
		super.readData(bcData);
		showSignals = bcData.readBoolean("showSignals");
	}

    public void switchSignals() {
        if (!level.isClientSide) {
            showSignals = !showSignals;
            markChunkDirty();
            sendNetworkUpdate(showSignals ? NET_SIGNALS_ON : NET_SIGNALS_OFF);
        }
    }

    private void readNewSignalState(boolean shouldShow) {
        boolean before = isActiveForRender();
        showSignals = shouldShow;
        if (before != isActiveForRender()) {
            redrawBlock();
        }
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                buffer.writeBoolean(showSignals);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_SIGNALS_ON) {
                readNewSignalState(true);
            } else if (id == NET_SIGNALS_OFF) {
                readNewSignalState(false);
            } else if (id == NET_RENDER_DATA) {
                readNewSignalState(buffer.readBoolean());
            }
        }
    }
    
    

    @Nonnull
    public AABB getRenderBoundingBox() {
        return new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }
    
    


    public void onManualConnectionAttempt(Player player) {
        MarkerSubCache<VolumeConnection> cache = this.getLocalCache();
        for (BlockPos other : cache.getValidConnections(getBlockPos())) {
            cache.tryConnect(getBlockPos(), other);
        }
        VolumeConnection c = getCurrentConnection();
        if (c != null) {
            for (BlockPos corner : PositionUtil.getCorners(c.getBox().min(), c.getBox().max())) {
                if (!c.getMarkerPositions().contains(corner) && cache.hasLoadedOrUnloadedMarker(corner)) {
                    c.addMarker(corner);
                }
            }
        }
    }

    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        refreshSignalStateFromWorld();
        // Check if we are the corner of an existing box
        MarkerSubCache<VolumeConnection> cache = this.getLocalCache();
        for (BlockPos other : cache.getValidConnections(getBlockPos())) {
            VolumeConnection c = cache.getConnection(other);
            if (c != null && c.getBox().isCorner(worldPosition)) {
                if (c.addMarker(worldPosition)) {
                    // In theory we can't be the corner for multiple boxes
                    break;
                }
            }
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("Min = " + min());
        left.add("Max = " + max());
        left.add("Signals = " + showSignals);
    }

    // ITileAreaProvider

    @Override
    public BlockPos min() {
        VolumeConnection connection = getCurrentConnection();
        return connection == null ? getBlockPos() : connection.getBox().min();
    }

    @Override
    public BlockPos max() {
        VolumeConnection connection = getCurrentConnection();
        return connection == null ? getBlockPos() : connection.getBox().max();
    }

    @Override
    public void removeFromWorld(Player player) {
        if (level == null || level.isClientSide) {
            return;
        }
        VolumeConnection connection = getCurrentConnection();
        if (connection != null) {
            // Copy the list over because the iterator doesn't like it if you change the connection while using it.
            // Claimed markers are always returned to the world, matching BC7/BC8 machine placement behaviour.
            List<BlockPos> allPositions = ImmutableList.copyOf(connection.getMarkerPositions());
            for (BlockPos p : allPositions) {
                removeClaimedMarkerBlock(p);
            }
        }
    }

    @Override
    public boolean isValidFromLocation(BlockPos pos) {
        VolumeConnection connection = getCurrentConnection();
        if (connection == null) {
            return false;
        }
        Box box = connection.getBox();
        if (box.contains(pos)) {
            return false;
        }
        for (BlockPos p : PositionUtil.getCorners(box.min(), box.max())) {
            if (PositionUtil.isNextTo(p, pos)) {
                return true;
            }
        }
        return false;
    }
}
