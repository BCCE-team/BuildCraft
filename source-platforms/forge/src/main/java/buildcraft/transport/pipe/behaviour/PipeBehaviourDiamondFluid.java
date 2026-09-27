/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.behaviour;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.PipeEventFluid;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.platform.storage.PlatformFluidPipeTransfer;

public class PipeBehaviourDiamondFluid extends PipeBehaviourDiamond {
    public PipeBehaviourDiamondFluid(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
    }

    public PipeBehaviourDiamondFluid(IPipe pipe) {
        super(pipe);
    }

    @PipeEventHandler
    public void sideCheck(PipeEventFluid.SideCheck sideCheck) {
        FluidStack toCompare = PlatformFluidPipeTransfer.toNative(sideCheck.fluid);
        if (toCompare.isEmpty()) return;
        for (Direction face : Direction.values()) {
            if (sideCheck.isAllowed(face) && pipe.isConnected(face)) {
                int offset = FILTERS_PER_SIDE * face.ordinal();
                boolean sideAllowed = false;
                boolean foundItem = false;
                for (int i = 0; i < FILTERS_PER_SIDE; i++) {
                    ItemStack compareTo = filters.getStackInSlot(offset + i);
                    if (compareTo.isEmpty()) continue;
                    FluidStack target = FluidUtil.getFluidContained(compareTo).orElse(FluidStack.EMPTY);
                    if (target.isEmpty() || target.getAmount() <= 0) {
                        continue;
                    }
                    foundItem = true;
                    if (FluidCompatRegistry.areEquivalent(target, toCompare)) {
                        sideAllowed = true;
                        break;
                    }
                }
                if (foundItem) {
                    if (sideAllowed) {
                        sideCheck.increasePriority(face, 12);
                    } else {
                        sideCheck.disallow(face);
                    }
                }
            }
        }
    }




    
    
}
