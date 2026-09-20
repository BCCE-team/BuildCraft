/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;
import java.util.List;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.core.BCCoreConfig;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.lib.migrate.BCVersion;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public abstract class TileMiner extends TileBC_Neptune implements IDebuggable {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("miner");
    public static final int NET_LED_STATUS = IDS.allocId("LED_STATUS");
    public static final int NET_WANTED_Y = IDS.allocId("WANTED_Y");

    protected long progress = 0;
    protected BlockPos currentPos = null;

    protected int wantedLength = 0;
    protected double currentLength = 0;
    protected double lastLength = 0;
    protected int offset;
    protected boolean shaftBlocked = false;

    protected boolean isComplete = false;
    protected final MjBattery battery = new MjBattery(getBatteryCapacity());
    private final AABB blockAABB = new AABB(0,0,0,1,1,1);


    public TileMiner(BlockEntityType<?> bet, BlockPos pos, BlockState state) {
    	super(bet, pos, state);
        caps.addProvider(new MjCapabilityHelper(createMjReceiver()));
        caps.addCapabilityInstance(TilesAPI.CAP_HAS_WORK, () -> !this.isComplete(), EnumPipePart.VALUES);
    }

    protected abstract void mine();

    protected abstract IMjReceiver createMjReceiver();

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public void update() {
        if (level.isClientSide) {
            lastLength = currentLength;
            if (Math.abs(wantedLength - currentLength) <= 0.01) {
                currentLength = wantedLength;
            } else {
                currentLength = currentLength + (wantedLength - currentLength) / 7D;
            }
            return;
        }

        battery.tick(getLevel(), getBlockPos());

        if (level.getGameTime() % 10 == offset) {
            sendNetworkUpdate(NET_LED_STATUS);
        }

        mine();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        offset = level.random.nextInt(10);
    }

    @Override
    public void onRemove(boolean dropSelf) {
        super.onRemove(dropSelf);
        for (int y = worldPosition.getY() - 1; y > worldPosition.getY() - BCCoreConfig.miningMaxDepth; y--) {
            BlockPos blockPos = new BlockPos(worldPosition.getX(), y, worldPosition.getZ());
            if (level.getBlockState(blockPos).getBlock() == BCFactoryBlocks.TUBE_BLOCK.get()) {
                level.setBlockAndUpdate(blockPos, Blocks.AIR.defaultBlockState());
            } else {
                break;
            }
        }
    }

    protected void updateLength() {
        int newY = getTargetPos() != null ? getTargetPos().getY() : worldPosition.getY();
        int newLength = worldPosition.getY() - newY;
        if (newLength != wantedLength || shaftBlocked) {
            for (int y = worldPosition.getY() - 1; y > worldPosition.getY() - BCCoreConfig.miningMaxDepth; y--) {
                BlockPos blockPos = new BlockPos(worldPosition.getX(), y, worldPosition.getZ());
                if (level.getBlockState(blockPos).getBlock() == BCFactoryBlocks.TUBE_BLOCK.get()) {
                    level.setBlockAndUpdate(blockPos, Blocks.AIR.defaultBlockState());
                } else {
                    break;
                }
            }
            shaftBlocked = false;
            int effectiveLength = newLength;
            for (int y = worldPosition.getY() - 1; y > newY; y--) {
                BlockPos blockPos = new BlockPos(worldPosition.getX(), y, worldPosition.getZ());
                BlockState existing = level.getBlockState(blockPos);
                if (existing.getBlock() == BCFactoryBlocks.TUBE_BLOCK.get()) {
                    continue;
                }
                if (existing.isAir() || BlockUtil.isReplaceable(level, blockPos)) {
                    level.setBlockAndUpdate(blockPos, BCFactoryBlocks.TUBE_BLOCK.get().defaultBlockState());
                    continue;
                }
                shaftBlocked = true;
                effectiveLength = Math.max(0, worldPosition.getY() - (y + 1));
                break;
            }
            currentLength = wantedLength = effectiveLength;
            sendNetworkUpdate(NET_WANTED_Y);
        }
    }

    protected BlockPos getTargetPos() {
        return currentPos;
    }

    public double getLength(float partialTicks) {
        if (partialTicks <= 0) {
            return lastLength;
        } else if (partialTicks >= 1) {
            return currentLength;
        } else {
            return lastLength * (1 - partialTicks) + currentLength * partialTicks;
        }
    }

    public boolean isComplete() {
        return level.isClientSide ? isComplete : currentPos == null;
    }

    @Override
    protected void migrateOldNBT(int version, CompoundTag nbt) {
        super.migrateOldNBT(version, nbt);
        if (version == BCVersion.BEFORE_RECORDS.dataVersion || version == BCVersion.v7_2_0_pre_12.dataVersion) {
            CompoundTag oldBattery = nbt.getCompound("battery");
            int energy = oldBattery.getInt("energy");
            battery.extractPower(0, Integer.MAX_VALUE);
            battery.addPower(energy * 100, FluidAction.EXECUTE);
        }
    }

    @Override
    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        super.writeData(bcData);
        if (currentPos != null) {
            bcData.writeLong("currentPos", currentPos.asLong());
        }
        bcData.writeInt("wantedLength", wantedLength);
        bcData.writeLong("progress", progress);
        nbt.put("battery", battery.serializeNBT(registries));
    }

    @Override
    protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        super.readData(bcData);
        if (bcData.has("currentPos")) {
            currentPos = BlockPos.of(bcData.readLong("currentPos"));
        }
        wantedLength = bcData.readInt("wantedLength");
        progress = Math.max(0L, bcData.readLong("progress"));
        // The persisted key "mj_battery" is accepted as a legacy battery alias.
        if (bcData.has("mj_battery")) {
            nbt.put("battery", nbt.get("mj_battery"));
        }
        battery.deserializeNBT(registries, bcData.readCompound("battery"));
    }

    // Networking

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_LED_STATUS, buffer, side);
                buffer.writeInt(wantedLength);
            } else if (id == NET_LED_STATUS) {
                buffer.writeBoolean(isComplete());
                battery.writeToBuffer(buffer);
            } else if (id == NET_WANTED_Y) {
                buffer.writeInt(wantedLength);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_LED_STATUS, buffer, side, ctx);
                currentLength = lastLength = wantedLength = buffer.readInt();
            } else if (id == NET_LED_STATUS) {
                isComplete = buffer.readBoolean();
                battery.readFromBuffer(buffer);
            } else if (id == NET_WANTED_Y) {
                wantedLength = buffer.readInt();
            }
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("battery = " + battery.getDebugString());
        left.add("current = " + currentPos);
        left.add("wantedLength = " + wantedLength);
        left.add("currentLength = " + currentLength);
        left.add("lastLength = " + lastLength);
        left.add("isComplete = " + isComplete());
        left.add("progress = " + LocaleUtil.localizeMj(progress));
    }

    // Rendering
    
    public AABB getRenderBoundingBox() {
		return blockAABB.move(worldPosition).expandTowards(0, 1-currentLength, 0);
	}
    public float getPercentFilledForRender() {
        float val = battery.getStored() / (float) battery.getCapacity();
        return val < 0 ? 0 : val > 1 ? 1 : val;
    }

    protected long getBatteryCapacity() {
        return 500 * MjAmount.MICRO_MJ_PER_MJ;
    }
}
