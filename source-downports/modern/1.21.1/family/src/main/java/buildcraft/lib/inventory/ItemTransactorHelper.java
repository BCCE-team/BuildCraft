/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.internal.inventory.IItemTransactor.IItemInsertable;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.PlatformStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public class ItemTransactorHelper {
    @Nonnull
    public static IItemTransactor getTransactor(BlockEntity provider, Direction face) {
        if (provider == null || provider.getLevel() == null) {
            return NoSpaceTransactor.INSTANCE;
        }
        return getTransactor(provider.getLevel(), provider.getBlockPos(), face, provider);
    }

    /**
     * Position-based item lookup for block capabilities. The provider is optional because a
     * NeoForge block capability may exist at a position that has no BlockEntity at all.
     */
    @Nonnull
    public static IItemTransactor getTransactor(Level level, BlockPos pos, Direction face, @Nullable BlockEntity provider) {
        if (level == null || pos == null) {
            return NoSpaceTransactor.INSTANCE;
        }

        IItemTransactor trans = level.getCapability(CapUtil.CAP_ITEM_TRANSACTOR, pos, face);
        if (trans != null) {
            return trans;
        }

        if (provider != null) {
            Container doubleChest = BlockUtil.getCombinedDoubleChestContainer(provider);
            if (doubleChest != null) {
                return new InventoryWrapper(doubleChest);
            }
        }

        ItemStorage storage = PlatformStorage.items(level, pos, face);
        if (storage != null) {
            return new ItemStorageTransactor(storage);
        }

        if (provider instanceof ISidedInventory sidedInventory) {
            return new SidedInventoryWrapper(sidedInventory, face);
        }
        if (provider instanceof Container container) {
            return new InventoryWrapper(container);
        }
        return NoSpaceTransactor.INSTANCE;
    }

    @Nonnull
    public static IItemTransactor getTransactor(Inventory inventory) {
        if (inventory == null) {
            return NoSpaceTransactor.INSTANCE;
        }
        return new InventoryWrapper(inventory);
    }

    @Nonnull
    public static IItemTransactor getTransactorForEntity(Entity entity, Direction face) {
        if (entity == null) {
            return NoSpaceTransactor.INSTANCE;
        }

        IItemTransactor transactor = entity.getCapability(CapUtil.CAP_ITEM_TRANSACTOR_ENTITY, face);
        if (transactor != null) {
            return transactor;
        }

        ItemStorage storage = PlatformStorage.items(entity, face);
        if (storage != null) {
            return new ItemStorageTransactor(storage);
        }
        if (entity instanceof ItemEntity itemEntity) {
            return new TransactorEntityItem(itemEntity);
        }
        if (entity instanceof AbstractArrow arrow) {
            return new TransactorEntityArrow(arrow);
        }
        return NoSpaceTransactor.INSTANCE;
    }

    @Nonnull
    public static IInjectable getInjectable(BlockEntity provider, Direction face) {
        if (provider == null || provider.getLevel() == null) {
            return NoSpaceInjectable.INSTANCE;
        }
        return getInjectable(provider.getLevel(), provider.getBlockPos(), face);
    }

    @Nonnull
    public static IInjectable getInjectable(Level level, BlockPos pos, Direction face) {
        if (level == null || pos == null) {
            return NoSpaceInjectable.INSTANCE;
        }
        IInjectable injectable = level.getCapability(PipeApi.CAP_INJECTABLE, pos, face);
        return injectable == null ? NoSpaceInjectable.INSTANCE : injectable;
    }

    public static IItemTransactor wrapInjectable(IInjectable injectable, Direction facing) {
        return new InjectableWrapper(injectable, facing);
    }

    /** Provides an implementation of {@link IItemTransactor#insert(NonNullList, boolean)} that relies on
     * {@link IItemTransactor#insert(ItemStack, boolean, boolean)}. This is the least efficient, default
     * implementation. */
    public static NonNullList<ItemStack> insertAllBypass(IItemTransactor transactor, NonNullList<ItemStack> stacks, boolean simulate) {
        NonNullList<ItemStack> leftOver = NonNullList.create();
        for (ItemStack stack : stacks) {
            ItemStack leftOverStack = transactor.insert(stack, false, simulate);
            if (!leftOverStack.isEmpty()) {
                leftOver.add(leftOverStack);
            }
        }
        return leftOver;
    }

    /** Attempts to move as many items as possible from the source {@link IItemTransactor} to the destination.
     * 
     * @return The number of items moved. */
    public static int move(IItemTransactor src, IItemTransactor dst) {
        return move(src, dst, Integer.MAX_VALUE);
    }

    /** Attempts to move up to maxItems from the source {@link IItemTransactor} to the destination.
     * 
     * @param maxItems The maximum number of items to move.
     * @return The number of items moved. */
    public static int move(IItemTransactor src, IItemTransactor dst, int maxItems) {
        return move(src, dst, null, maxItems);
    }

    /** Attempts to move up to maxItems from the source {@link IItemTransactor} to the destination.
     * 
     * @return The number of items moved. */
    public static int move(IItemTransactor src, IItemTransactor dst, IStackFilter filter) {
        return move(src, dst, filter, Integer.MAX_VALUE);
    }

    /** Attempts to move up to maxItems from the source {@link IItemTransactor} to the destination.
     * 
     * @param filter The stack filter to use - only items that match this filter will be moved.
     * @param maxItems The maximum number of items to move.
     * @return The number of items moved. */
    public static int move(IItemTransactor src, IItemTransactor dst, IStackFilter filter, int maxItems) {
        int moved = 0;
        IStackFilter rFilter = dst::canPartiallyAccept;
        if (filter != null) {
            rFilter = rFilter.and(filter);
        }
        while (true) {
            int m = moveSingle0(src, dst, rFilter, maxItems - moved, false, false);
            if (m == 0) {
                break;
            } else {
                moved += m;
            }
        }
        return moved;
    }

    public static int moveSingle(IItemTransactor src, IItemTransactor dst, IStackFilter filter, boolean simulateSrc, boolean simulateDst) {
        return moveSingle(src, dst, filter, Integer.MAX_VALUE, simulateSrc, simulateDst);
    }

    /** Similar to {@link #move(IItemTransactor, IItemTransactor, IStackFilter, int)}, but will only attempt to extract
     * and insert once, which means that you can simulate the move safely. */
    public static int moveSingle(IItemTransactor src, IItemTransactor dst, IStackFilter filter, int maxItems, boolean simulateSrc, boolean simulateDst) {
        IStackFilter rFilter = dst::canPartiallyAccept;
        if (filter != null) {
            rFilter = rFilter.and(filter);
        }
        return moveSingle0(src, dst, rFilter, maxItems, simulateSrc, simulateDst);
    }

    private static int moveSingle0(IItemTransactor src, IItemTransactor dst, IStackFilter filter, int maxItems, boolean simulateSrc, boolean simulateDst) {
        if (maxItems <= 0) return 0;

        ItemStack potential = src.extract(filter, 1, maxItems, true);
        if (potential.isEmpty()) return 0;

        ItemStack simulatedLeftOver = dst.insert(potential.copy(), false, true);
        int toTake = potential.getCount() - simulatedLeftOver.getCount();
        if (toTake <= 0) return 0;

        IStackFilter exactFilter = stack -> StackUtil.canMerge(stack, potential);
        ItemStack stillAvailable = src.extract(exactFilter, toTake, toTake, true);
        if (stillAvailable.getCount() != toTake || !StackUtil.canMerge(stillAvailable, potential)) {
            return 0;
        }

        // A transfer is one transaction. Mixed simulation modes cannot be committed safely: executing only the
        // destination duplicates items, while executing only the source deletes them. Treat either flag as a full
        // dry-run and report how many items the transaction could move.
        if (simulateSrc || simulateDst) {
            return toTake;
        }

        // Execute source-first. If a mutable/modded destination accepts less than it promised during simulation,
        // return the remainder to the source instead of leaving a duplicated destination stack behind.
        ItemStack taken = src.extract(exactFilter, toTake, toTake, false);
        if (taken.isEmpty() || !StackUtil.canMerge(taken, potential)) {
            if (!taken.isEmpty()) {
                rollbackToSource(src, taken, "source returned a different item during extraction");
            }
            return 0;
        }

        ItemStack leftOver;
        try {
            leftOver = dst.insert(taken.copy(), false, false);
        } catch (RuntimeException exception) {
            rollbackToSource(src, taken, "destination threw while accepting items");
            buildcraft.lib.internal.debug.BCLog.logger.warn("A destination item transactor threw while BuildCraft was moving items", exception);
            return 0;
        }

        int accepted = Math.max(0, taken.getCount() - leftOver.getCount());
        if (accepted < taken.getCount()) {
            ItemStack remainder = taken.copy();
            remainder.setCount(taken.getCount() - accepted);
            rollbackToSource(src, remainder, "destination accepted less than it simulated");
        }
        return accepted;
    }

    private static void rollbackToSource(IItemTransactor src, ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        ItemStack leftOver;
        try {
            leftOver = src.insert(stack.copy(), false, false);
        } catch (RuntimeException exception) {
            buildcraft.lib.internal.debug.BCLog.logger.error("Item transfer rollback failed because the source threw (" + reason + ")", exception);
            return;
        }
        if (!leftOver.isEmpty()) {
            buildcraft.lib.internal.debug.BCLog.logger.error(
                "Item transfer rollback was only partially accepted by " + src.getClass().getName()
                    + " (" + reason + "): " + leftOver
            );
        }
    }

    public static IItemInsertable createDroppingTransactor(Level world, Vec3 vec) {
        return (stack, allorNone, simulate) -> {
            if (!simulate) {
                InventoryUtil.drop(world, vec, stack);
            }
            return StackUtil.EMPTY;
        };
    }
}
