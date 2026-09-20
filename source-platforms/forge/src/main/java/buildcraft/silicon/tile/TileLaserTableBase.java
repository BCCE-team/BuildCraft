/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.api.v2.machine.LaserTarget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.enums.EnumLaserTableType;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.data.AverageLong;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public abstract class TileLaserTableBase extends TileBC_Neptune implements LaserTarget, IDebuggable {
    private static final long MJ_FLOW_ROUND = MjAmount.MICRO_MJ_PER_MJ / 10;
    private final AverageLong avgPower = new AverageLong(120);
    public long avgPowerClient;
    public long targetClient;
    public long power;

    private final MjPort api2LaserPort = new MjPort() {
        @Override
        public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            long accepted = Math.min(offered.microMj(), getDirectPowerRequested());
            if (accepted > 0 && mode == OperationMode.EXECUTE) {
                power += accepted;
                avgPower.push(accepted);
                markChunkDirty();
            }
            return MjTransferResult.of(offered, MjAmount.ofMicro(accepted));
        }

        @Override public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            return MjTransferResult.none(requested);
        }
        @Override public MjAmount stored() { return MjAmount.ofMicro(Math.max(0L, power)); }
        @Override public MjAmount capacity() { return MjAmount.ofMicro(Math.max(power, Math.max(0L, getTarget()))); }
        @Override public boolean canInsert() { return true; }
        @Override public boolean canExtract() { return false; }
    };

    protected TileLaserTableBase(BlockEntityType<? extends TileLaserTableBase> type, BlockPos pos, BlockState state) {
    	super(type, pos, state);
        caps.addCapabilityInstance(TilesAPI.CAP_HAS_WORK, () -> getTarget() > 0, EnumPipePart.VALUES);
    }

    public abstract long getTarget();

    public long getGuiTarget() {
        return level != null && level.isClientSide ? targetClient : getTarget();
    }

    @Override
    public MjPort laserPort() {
        return api2LaserPort;
    }

    @Override
    public java.util.Optional<net.minecraft.resources.ResourceLocation> typeId() {
        BlockState state = getBlockState();
        if (!state.hasProperty(BuildCraftProperties.LASER_TABLE_TYPE)) return java.util.Optional.empty();
        EnumLaserTableType type = state.getValue(BuildCraftProperties.LASER_TABLE_TYPE);
        return java.util.Optional.of(switch (type) {
            case ASSEMBLY_TABLE -> BuildCraftContentIds.LaserTables.ASSEMBLY;
            case ADVANCED_CRAFTING_TABLE -> BuildCraftContentIds.LaserTables.ADVANCED_CRAFTING;
            case INTEGRATION_TABLE -> BuildCraftContentIds.LaserTables.INTEGRATION;
            case CHARGING_TABLE -> BuildCraftContentIds.LaserTables.CHARGING;
            case PROGRAMMING_TABLE -> BuildCraftContentIds.LaserTables.PROGRAMMING;
        });
    }

    @Override
    public void update() {
        avgPower.tick();
        if (level.isClientSide) {
            return;
        }

        if (getTarget() <= 0) {
            power = 0;
            avgPower.clear();
        }
    }
    
	@Override
	public void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
        nbt.putLong("power", power);
	}
	
    @Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
		power = nbt.getLong("power");
	}


    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_GUI_TICK) {
                buffer.writeLong(power);
                buffer.writeLong(getTarget());
                double avg = avgPower.getAverage();
                long pwrAvg = Math.round(avg);
                long div = pwrAvg / MJ_FLOW_ROUND;
                long mod = pwrAvg % MJ_FLOW_ROUND;
                int mj = (int) (div) + ((mod > MJ_FLOW_ROUND / 2) ? 1 : 0);
                buffer.writeInt(mj);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_GUI_TICK) {
                power = buffer.readLong();
                targetClient = buffer.readLong();
                avgPowerClient = buffer.readInt() * MJ_FLOW_ROUND;
            }
        }
    }


    protected long getDirectPowerRequested() {
        return Math.max(0, getTarget() - power);
    }

    protected long receiveDirectPower(long microJoules, FluidAction action) {
        long accepted = Math.min(getDirectPowerRequested(), microJoules);
        if (accepted > 0 && action.execute()) {
            power += accepted;
            avgPower.push(accepted);
            markChunkDirty();
        }
        return microJoules - accepted;
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("power - " + LocaleUtil.localizeMj(power));
        left.add("target - " + LocaleUtil.localizeMj(getTarget()));
    }

    protected boolean extract(ItemHandlerSimple inv, Collection<IngredientStack> items, boolean simulate,
        boolean precise) {
        List<ItemStack> working = new ArrayList<>(inv.getSlots());
        long remainingStacks = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack copy = inv.getStackInSlot(i).copy();
            working.add(copy);
            if (!copy.isEmpty()) {
                remainingStacks++;
            }
        }

        for (IngredientStack definition : items) {
            int remaining = definition.count;
            for (int i = 0; i < inv.getSlots() && remaining > 0; i++) {
                ItemStack slotStack = working.get(i);
                if (slotStack.isEmpty()) continue;
                if (definition.ingredient.test(slotStack)) {
                    int spend = Math.min(remaining, slotStack.getCount());
                    remaining -= spend;
                    slotStack.shrink(spend);
                    if (slotStack.isEmpty()) {
                        working.set(i, ItemStack.EMPTY);
                    }
                }
            }
            if (remaining != 0) {
                return false;
            }
            remainingStacks--;
        }
        if (precise && remainingStacks != 0) {
            return false;
        }
        if (!simulate) {
            for (int i = 0; i < working.size(); i++) {
                inv.setStackInSlot(i, working.get(i));
            }
        }
        return true;
    }
}
