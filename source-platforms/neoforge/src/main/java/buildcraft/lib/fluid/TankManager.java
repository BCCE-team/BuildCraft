/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
//? if mc_26_x {
 *
//? } else {
 * 
//?}
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.lib.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.lib.internal.core.IFluidHandlerAdv;
import buildcraft.lib.fluid.FluidDropRuntime;
import buildcraft.lib.misc.FluidUtilBC;
import com.google.common.collect.ForwardingList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
//? if mc_26_x {
import buildcraft.lib.compat.neoforge2612.common.util.INBTSerializable;
//? } else {
import net.neoforged.neoforge.common.util.INBTSerializable;
//?}
import net.neoforged.neoforge.fluids.FluidStack;
//? if mc_26_x {
import buildcraft.lib.compat.NbtCompat;
//? } else {
//?}

/** Provides a simple way to save+load and send+receive data for any number of tanks. This also attempts to fill all of
 * the tanks one by one via the {@link #fill(FluidStack, boolean)} and {@link #drain(FluidStack, boolean)} methods. */
//? if mc_26_x {
public class TankManager extends ForwardingList<Tank> implements buildcraft.lib.compat.transfer.IndexedFluidHandler, IFluidHandlerAdv, INBTSerializable<CompoundTag> {
//? } else {
public class TankManager extends ForwardingList<Tank> implements IFluidHandlerAdv, INBTSerializable<CompoundTag> {
//?}

    private final ArrayList<Tank> tanks = new ArrayList<>();

    public TankManager() {}

    public TankManager(Tank... tanks) {
        addAll(Arrays.asList(tanks));
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    protected List<Tank> delegate() {
        return tanks;
    }

    public void addAll(Tank... values) {
        Collections.addAll(tanks, values);
        tanks.trimToSize();
    }
//? if mc_26_x {

//? } else {
    
//?}
    public void addLast(Tank value) {
//? if mc_26_x {
        tanks.add(value);
        tanks.trimToSize();
//? } else {
    	tanks.add(value);
    	tanks.trimToSize();
//?}
    }

    public void addDrops(NonNullList<ItemStack> toDrop) {
//? if mc_26_x {
        if(tanks.isEmpty())
            return;
//? } else {
    	if(tanks.isEmpty())
    		return;
//?}
        FluidDropRuntime.addFluidDrops(toDrop, toArray(new Tank[0]));
    }

    public InteractionResult onActivated(Player player, BlockPos pos, InteractionHand hand) {
//        return FluidUtilBC.onTankActivated(player, pos, hand, this);
//? if mc_26_x {
        return FluidUtilBC.onTankActivated(player, pos, hand, this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        //return FluidUtil.interactWithFluidHandler(player, hand, this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
//? } else {
    	return FluidUtilBC.onTankActivated(player, pos, hand, this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    	//return FluidUtil.interactWithFluidHandler(player, hand, this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
//?}
    }

    private List<Tank> getFillOrderTanks() {
        List<Tank> list = new ArrayList<>();
        for (Tank t : tanks) {
            if (t.canFill() && !t.canDrain()) {
                list.add(t);
            }
        }
        for (Tank t : tanks) {
            if (t.canFill() && t.canDrain()) {
                list.add(t);
            }
        }
        return list;
    }

    private List<Tank> getDrainOrderTanks() {
        List<Tank> list = new ArrayList<>();
        for (Tank t : tanks) {
            if (!t.canFill() && t.canDrain()) {
                list.add(t);
            }
        }
        for (Tank t : tanks) {
            if (t.canFill() && t.canDrain()) {
                list.add(t);
            }
        }
        return list;
    }

//? if mc_26_x {
    public int fillTank(int index, FluidStack resource, FluidAction action) {
        return tanks.get(index).fill(resource, action);
    }

    public FluidStack drainTank(int index, FluidStack resource, FluidAction action) {
        return tanks.get(index).drain(resource, action);
    }

//? } else {
    @Override
//?}
    public int fill(FluidStack resource, FluidAction doFill) {
        int filled = 0;
        for (Tank tank : getFillOrderTanks()) {
            int used = tank.fill(resource, doFill);
            if (used > 0) {
                resource = resource.copy();
                resource.setAmount(resource.getAmount() - used);
                filled += used;
                if (resource.getAmount() <= 0) {
                    return filled;
                }
            }
        }
        return filled;
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public FluidStack drain(FluidStack resource, FluidAction doDrain) {
        if (resource == null || resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        FluidStack draining = FluidStack.EMPTY;
        int left = resource.getAmount();
        for (Tank tank : getDrainOrderTanks()) {
            if (left <= 0) {
                break;
            }
            if (!FluidCompatRegistry.areEquivalent(resource, tank.getFluid())) {
                continue;
            }
            FluidStack drained = tank.drain(left, doDrain);
            if (drained == null || drained.isEmpty()) {
                continue;
            }
            if (draining.isEmpty()) {
                draining = resource.copyWithAmount(drained.getAmount());
            } else {
                draining.grow(drained.getAmount());
            }
            left -= drained.getAmount();
        }
        return draining;
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public FluidStack drain(int maxDrain, FluidAction doDrain) {
        FluidStack draining = FluidStack.EMPTY;
        for (Tank tank : getDrainOrderTanks()) {
            if (draining.isEmpty()) {
                FluidStack drained = tank.drain(maxDrain, doDrain);
                if (!drained.isEmpty() && drained.getAmount() > 0) {
                    draining = drained;
                    maxDrain -= drained.getAmount();
                }
            } else if (FluidCompatRegistry.areEquivalent(draining, tank.getFluid())) {
                FluidStack drained = tank.drain(maxDrain, doDrain);
                if (!drained.isEmpty() && drained.getAmount() > 0) {
                    draining.setAmount(draining.getAmount() + drained.getAmount());
                    maxDrain -= drained.getAmount();
                }
            }
        }
        return draining;
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public FluidStack drain(IFluidFilter filter, int maxDrain, FluidAction doDrain) {
        if (filter == null) {
            return FluidStack.EMPTY;
        }
        FluidStack draining = FluidStack.EMPTY;
        for (Tank tank : getDrainOrderTanks()) {
            if (!filter.matches(tank.getFluid())) {
                continue;
            }
            if (draining.isEmpty()) {
                FluidStack drained = tank.drain(maxDrain, doDrain);
                if (!drained.isEmpty() && drained.getAmount() > 0) {
                    draining = drained;
                    maxDrain -= drained.getAmount();
                }
            } else if (FluidCompatRegistry.areEquivalent(draining, tank.getFluid())) {
                FluidStack drained = tank.drain(maxDrain, doDrain);
                if (!drained.isEmpty() && drained.getAmount() > 0) {
                    draining.setAmount(draining.getAmount() + drained.getAmount());
                    maxDrain -= drained.getAmount();
                }
            }
        }
        return draining;
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        for (Tank t : tanks) {
            nbt.put(t.getTankName(), t.serializeNBT());
        }
        return nbt;
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag nbt) {
        for (Tank t : tanks) {
//? if mc_26_x {
            t.readFromNBT(NbtCompat.getCompound(nbt, t.getTankName()));
//? } else {
            t.readFromNBT(nbt.getCompound(t.getTankName()));
//?}
        }
    }

    public void writeData(FriendlyByteBuf buffer) {
        for (Tank tank : tanks) {
            tank.writeToBuffer(buffer);
        }
    }
//? if mc_26_x {

//? } else {
//?}
    public void readData(FriendlyByteBuf buffer) {
        for (Tank tank : tanks) {
            tank.readFromBuffer(buffer);
        }
    }

//? if mc_26_x {
    public int getTanks() {
        return tanks.size();
    }
//? } else {
	@Override
	public int getTanks() {
		return tanks.size();
	}
//?}

//? if mc_26_x {
    public @NotNull FluidStack getFluidInTank(int tank) {
        if (tank < 0 || tank >= tanks.size()) {
            return FluidStack.EMPTY;
        }
        return tanks.get(tank).getFluid().copy();
    }
//? } else {
	@Override
	public @NotNull FluidStack getFluidInTank(int tank) {
		if (tank < 0 || tank >= tanks.size()) {
			return FluidStack.EMPTY;
		}
		return tanks.get(tank).getFluid().copy();
	}
//?}

//? if mc_26_x {
    public int getTankCapacity(int tank) {
        if (tank < 0 || tank >= tanks.size()) {
            return 0;
        }
        return tanks.get(tank).getCapacity();
    }
//? } else {
	@Override
	public int getTankCapacity(int tank) {
		if (tank < 0 || tank >= tanks.size()) {
			return 0;
		}
		return tanks.get(tank).getCapacity();
	}
//?}

//? if mc_26_x {
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return tank >= 0 && tank < tanks.size() && tanks.get(tank).isFluidValid(stack);
    }
//? } else {
	@Override
	public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
		return tank >= 0 && tank < tanks.size() && tanks.get(tank).isFluidValid(stack);
	}
//?}

//? if mc_26_x {
    public boolean add(Tank e) {
        return tanks.add(e);
    }
//? } else {
	@Override
	public boolean add(Tank e) {
		return tanks.add(e);
	}
//?}

//? if mc_26_x {
    public boolean addAll(Collection<? extends Tank> c) {
        return tanks.addAll(c);
    }
//? } else {
	@Override
	public boolean addAll(Collection<? extends Tank> c) {
		return tanks.addAll(c);
	}
//?}
}
