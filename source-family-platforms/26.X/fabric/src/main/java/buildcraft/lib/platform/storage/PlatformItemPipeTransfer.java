/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.storage;

import javax.annotation.Nullable;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fabric 26.3 pipe/item inventory interop via the canonical BuildCraft API2.
 * The old 1.20.1 Fabric implementation was excluded from effective sources
 * and depended on the removed OperationScope API. This is an active 26.X
 * adapter, not a placeholder.
 */
public final class PlatformItemPipeTransfer {
    private PlatformItemPipeTransfer() {}

    @Nullable
    private static ItemPort port(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        return BuildCraftApi.service(BuildCraftServices.PLATFORM)
            .itemTransfer()
            .flatMap(transfer -> transfer.find(level, pos, side))
            .orElse(null);
    }

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        return port(level, pos, side) != null;
    }

    public static ItemStack extract(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side,
        IStackFilter filter, int min, int max, OperationMode mode
    ) {
        if (max <= 0 || min < 0 || min > max) return ItemStack.EMPTY;
        ItemPort target = port(level, pos, side);
        if (target == null) return ItemStack.EMPTY;
        ItemMatcher matcher = stack -> filter == null || filter.matches(stack);
        ItemTransferResult simulated = target.extract(matcher, max, OperationMode.SIMULATE);
        if (simulated.transferredCount() < min) return ItemStack.EMPTY;
        if (mode == OperationMode.SIMULATE) return simulated.transferred();

        // A parent transaction makes the minimum guarantee atomic even if a
        // sided handler's available amount changes after the initial probe.
        try (Transaction transaction = FabricTransactionScope.begin()) {
            ItemTransferResult executed = target.extract(matcher, max, OperationMode.EXECUTE);
            if (executed.transferredCount() < min || executed.transferredCount() == 0) {
                return ItemStack.EMPTY;
            }
            transaction.commit();
            return executed.transferred();
        }
    }

    /**
     * Return the uninserted stack with its original Data Components intact.
     * Colour and speed control pipe routing; they must not mutate a target's
     * item identity during an inventory handoff.
     */
    public static ItemStack insert(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side,
        ItemStack stack, @Nullable DyeColor colour, double speed, OperationMode mode
    ) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemPort target = port(level, pos, side);
        if (target == null) return stack;
        ItemTransferResult result = target.insert(stack, mode);
        int remaining = Math.max(0, stack.getCount() - result.transferredCount());
        if (remaining == 0) return ItemStack.EMPTY;
        ItemStack remainder = stack.copy();
        remainder.setCount(remaining);
        return remainder;
    }
}
