//? source if >=1.21.11
/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
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
import buildcraft.lib.compat.neoforge121111.common.util.INBTSerializable;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.lib.compat.NbtCompat;

/** Provides a simple way to save+load and send+receive data for any number of tanks. This also attempts to fill all of
 * the tanks one by one via the {@link #fill(FluidStack, boolean)} and {@link #drain(FluidStack, boolean)} methods. */
public class TankManager extends ForwardingList<Tank> implements buildcraft.lib.compat.transfer.IndexedFluidHandler, IFluidHandlerAdv, INBTSerializable<CompoundTag> {

    private final ArrayList<Tank> tanks = new ArrayList<>();

    public TankManager() {}

    public TankManager(Tank... tanks) {
        addAll(Arrays.asList(tanks));
    }

    protected List<Tank> delegate() {
        return tanks;
    }

    public void addAll(Tank... values) {
        Collections.addAll(tanks, values);
        tanks.trimToSize();
    }

    public void addLast(Tank value) {
        tanks.add(value);
        tanks.trimToSize();
    }

    public void addDrops(NonNullList<ItemStack> toDrop) {
        if(tanks.isEmpty())
            return;
        FluidDropRuntime.addFluidDrops(toDrop, toArray(new Tank[0]));
    }

    public InteractionResult onActivated(Player player, BlockPos pos, InteractionHand hand) {
//        return FluidUtilBC.onTankActivated(player, pos, hand, this);
        return FluidUtilBC.onTankActivated(player, pos, hand, this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        //return FluidUtil.interactWithFluidHandler(player, hand, this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
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

    public int fillTank(int index, FluidStack resource, FluidAction action) {
        return tanks.get(index).fill(resource, action);
    }

    public FluidStack drainTank(int index, FluidStack resource, FluidAction action) {
        return tanks.get(index).drain(resource, action);
    }

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

    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        for (Tank t : tanks) {
            nbt.put(t.getTankName(), t.serializeNBT());
        }
        return nbt;
    }

    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag nbt) {
        for (Tank t : tanks) {
            t.readFromNBT(NbtCompat.getCompound(nbt, t.getTankName()));
        }
    }

    public void writeData(FriendlyByteBuf buffer) {
        for (Tank tank : tanks) {
            tank.writeToBuffer(buffer);
        }
    }

    public void readData(FriendlyByteBuf buffer) {
        for (Tank tank : tanks) {
            tank.readFromBuffer(buffer);
        }
    }

    public int getTanks() {
        return tanks.size();
    }

    public @NotNull FluidStack getFluidInTank(int tank) {
        if (tank < 0 || tank >= tanks.size()) {
            return FluidStack.EMPTY;
        }
        return tanks.get(tank).getFluid().copy();
    }

    public int getTankCapacity(int tank) {
        if (tank < 0 || tank >= tanks.size()) {
            return 0;
        }
        return tanks.get(tank).getCapacity();
    }

    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return tank >= 0 && tank < tanks.size() && tanks.get(tank).isFluidValid(stack);
    }

    public boolean add(Tank e) {
        return tanks.add(e);
    }

    public boolean addAll(Collection<? extends Tank> c) {
        return tanks.addAll(c);
    }
}
