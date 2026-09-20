/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import buildcraft.lib.platform.storage.StorageAdapters;
import buildcraft.lib.platform.storage.FilteredFluidStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.compat.CompatCapTransfromer;
import buildcraft.core.BCCoreItems;
import buildcraft.core.item.ItemFragileFluidContainer;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.fluid.TankManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public class FluidUtilBC {

    public static void pushFluidAround(BlockGetter world, BlockPos pos, Tank tank) {
        for (Direction side : Direction.values()) {
            FluidStack available = tank.drainInternal(tank.getFluidAmount(), FluidAction.SIMULATE);
            if (available.isEmpty() || available.getAmount() <= 0) {
                return;
            }

            BlockEntity target = world.getBlockEntity(pos.relative(side));
            if (target == null) {
                continue;
            }
            FluidStorage<FluidStack> handler = StorageAdapters.fromNativeFluids(CompatCapTransfromer.INSTANCE
                .getCap(target, CapUtil.CAP_FLUIDS, side.getOpposite())
                .orElse(null));
            if (handler == null) {
                continue;
            }

            int accepted = Math.min(available.getAmount(), Math.max(0,
                handler.fill(available.copy(), true)));
            if (accepted <= 0) {
                continue;
            }

            // Drain the source before filling the destination so a handler change between simulation and execution
            // cannot create fluid.
            FluidStack drained = tank.drainInternal(available.copyWithAmount(accepted), FluidAction.EXECUTE);
            if (drained.isEmpty() || drained.getAmount() <= 0) {
                continue;
            }

            int actuallyAccepted = Math.min(drained.getAmount(), Math.max(0,
                handler.fill(drained.copy(), false)));
            if (actuallyAccepted < drained.getAmount()) {
                FluidStack remainder = drained.copyWithAmount(drained.getAmount() - actuallyAccepted);
                int restored = tank.fillInternal(remainder, FluidAction.EXECUTE);
                if (restored != remainder.getAmount()) {
                    BCLog.logger.error("Failed to roll back {} mB after a destination fluid handler accepted only {} mB",
                        remainder.getAmount(), actuallyAccepted);
                }
            }
        }
    }

    public static List<FluidStack> mergeSameFluids(List<FluidStack> fluids) {
        List<FluidStack> stacks = new ArrayList<>();
        fluids.forEach(toAdd -> {
            boolean found = false;
            for (FluidStack stack : stacks) {
                if (FluidCompatRegistry.areEquivalent(stack, toAdd)) {
                    stack.setAmount(stack.getAmount() + toAdd.getAmount());
                    found = true;
                }
            }
            if (!found&&!toAdd.isEmpty()) {
                stacks.add(toAdd.copy());
            }
        });
        return stacks;
    }

    public static boolean areFluidStackEqual(FluidStack a, FluidStack b) {
        return (a == FluidStack.EMPTY && b == FluidStack.EMPTY) || (a != FluidStack.EMPTY && FluidCompatRegistry.areEquivalent(a, b) && a.getAmount() == b.getAmount());
    }

    public static boolean areFluidsEqual(Fluid a, Fluid b) {
        if (a == Fluids.EMPTY || b == Fluids.EMPTY) {
            return a == b;
        }
        return FluidCompatRegistry.areEquivalent(a, b);
    }

    /** @return The fluidstack that was moved, or null if no fluid was moved. */
    @Nullable
    public static FluidStack move(IFluidHandler from, IFluidHandler to) {
        return move(from, to, Integer.MAX_VALUE);
    }

    /** @param max The maximum amount of fluid to move.
     * @return The fluidstack that was moved, or null if no fluid was moved. */
    @Nullable
    public static FluidStack move(IFluidHandler from, IFluidHandler to, int max) {
        return moveStorage(StorageAdapters.fromNativeFluids(from), StorageAdapters.fromNativeFluids(to), max);
    }

    public static FluidStack moveStorage(FluidStorage<FluidStack> from, FluidStorage<FluidStack> to, int max) {
        if (from == null || to == null || max <= 0) {
            return FluidStack.EMPTY;
        }

        FluidStack potential;
        if (from instanceof FilteredFluidStorage<FluidStack>) {
            java.util.function.Predicate<FluidStack> filter = fluid -> to.fill(fluid, true) > 0;
            potential = ((FilteredFluidStorage<FluidStack>) from).drain(filter, max, true);
        } else {
            potential = from.drain(max, true);
        }
        if (potential.isEmpty() || potential.getAmount() <= 0) {
            return FluidStack.EMPTY;
        }

        int accepted = Math.min(potential.getAmount(), Math.max(0,
            to.fill(potential.copy(), true)));
        if (accepted <= 0) {
            return FluidStack.EMPTY;
        }

        FluidStack requested = potential.copyWithAmount(accepted);
        FluidStack stillAvailable = from.drain(requested.copy(), true);
        if (!sameFluidAndAmount(requested, stillAvailable)) {
            return FluidStack.EMPTY;
        }

        // Source-first execution keeps the transfer conservative. If the destination accepts less
        // than it simulated, return the remainder to the source.
        FluidStack drained = from.drain(requested.copy(), false);
        if (drained.isEmpty() || drained.getAmount() <= 0) {
            return FluidStack.EMPTY;
        }
        if (!FluidCompatRegistry.areEquivalent(requested, drained)) {
            restoreFluid(StorageAdapters.toNativeFluids(from), drained, "source returned a different fluid");
            return FluidStack.EMPTY;
        }

        int actuallyAccepted;
        try {
            actuallyAccepted = to.fill(drained.copy(), false);
        } catch (RuntimeException exception) {
            restoreFluid(StorageAdapters.toNativeFluids(from), drained, "destination threw while accepting fluid");
            BCLog.logger.warn("A destination fluid handler threw while BuildCraft was moving fluid", exception);
            return FluidStack.EMPTY;
        }
        actuallyAccepted = Math.min(drained.getAmount(), Math.max(0, actuallyAccepted));

        if (actuallyAccepted < drained.getAmount()) {
            FluidStack remainder = drained.copyWithAmount(drained.getAmount() - actuallyAccepted);
            restoreFluid(StorageAdapters.toNativeFluids(from), remainder, "destination accepted less than it simulated");
        }
        return actuallyAccepted <= 0 ? FluidStack.EMPTY : drained.copyWithAmount(actuallyAccepted);
    }

    private static boolean sameFluidAndAmount(FluidStack expected, FluidStack actual) {
        return !expected.isEmpty() && !actual.isEmpty()
            && expected.getAmount() == actual.getAmount()
            && FluidCompatRegistry.areEquivalent(expected, actual);
    }

    private static void restoreFluid(IFluidHandler handler, FluidStack fluid, String reason) {
        if (fluid.isEmpty() || fluid.getAmount() <= 0) {
            return;
        }
        int restored;
        try {
            if (handler instanceof Tank) {
                restored = ((Tank) handler).fillInternal(fluid.copy(), FluidAction.EXECUTE);
            } else if (handler instanceof TankManager) {
                restored = restoreToTankManager((TankManager) handler, fluid);
            } else {
                restored = handler.fill(fluid.copy(), FluidAction.EXECUTE);
            }
            restored = Math.min(fluid.getAmount(), Math.max(0, restored));
        } catch (RuntimeException exception) {
            BCLog.logger.error("Failed to roll back fluid after {}", reason, exception);
            return;
        }
        if (restored != fluid.getAmount()) {
            BCLog.logger.error("Restored only {} of {} mB after {}", restored, fluid.getAmount(), reason);
        }
    }

    private static int restoreToTankManager(TankManager manager, FluidStack fluid) {
        FluidStack remaining = fluid.copy();
        int restored = 0;

        // Prefer the tank that already contains this fluid, which is normally the exact tank the transfer drained.
        for (Tank tank : manager) {
            if (remaining.isEmpty() || tank.getFluid().isEmpty()
                || !FluidCompatRegistry.areEquivalent(tank.getFluid(), remaining)) {
                continue;
            }
            int filled = tank.fillInternal(remaining, FluidAction.EXECUTE);
            restored += filled;
            remaining.shrink(filled);
        }
        for (Tank tank : manager) {
            if (remaining.isEmpty() || !tank.getFluid().isEmpty()) {
                continue;
            }
            int filled = tank.fillInternal(remaining, FluidAction.EXECUTE);
            restored += filled;
            remaining.shrink(filled);
        }
        return restored;
    }

    public static boolean onTankActivated(Player player, BlockPos pos, InteractionHand hand,
        IFluidHandler fluidHandler) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            return false;
        }

        // NeoForge already owns item-fluid container transactions. Use that path for BuildCraft buckets and fragile
        // shards as well instead of manually filling the machine and separately trying to rewrite the player's hand.
        // Keep BC8's interaction-claim behaviour so rejected buckets cannot fall through to world placement.
        boolean fluidContainer = FluidUtil.getFluidHandler(held.copy()).isPresent()
            || held.getItem() instanceof BucketItem
            || held.getItem() instanceof ItemFragileFluidContainer;
        if (!fluidContainer) {
            return false;
        }
        if (player.level().isClientSide) {
            return true;
        }

        boolean changed = FluidUtil.interactWithFluidHandler(player, hand, fluidHandler);
        if (changed) {
            syncPlayerInventory(player);
        }
        return true;
    }

    private static void syncPlayerInventory(Player player) {
        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
    }

    public static ItemStack getFragileFluid(FluidStack fluid) {
    //	if(fluid.isEmpty())
 //   		return ItemStack.EMPTY;
        ItemStack item = new ItemStack(BCCoreItems.FRAGILE_FLUID_SHARD.get());
        ItemFragileFluidContainer.setFluid(item, fluid);
        return item;
    }
}
