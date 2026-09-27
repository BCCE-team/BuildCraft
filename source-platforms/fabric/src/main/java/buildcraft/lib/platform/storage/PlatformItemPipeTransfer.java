package buildcraft.lib.platform.storage;
// Loader boundary owner: net.fabricmc (Transfer API semantics are delegated through PlatformStorage).

import javax.annotation.Nullable;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.OperationScope;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Fabric Transfer API adapter used by loader-neutral item-pipe gameplay. */
public final class PlatformItemPipeTransfer {
    private PlatformItemPipeTransfer() {}

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        return level != null && pos != null && PlatformStorage.itemTransfer(level, pos, side) != null;
    }

    public static ItemStack extract(
        Level level,
        BlockPos pos,
        @Nullable BlockEntity tile,
        Direction side,
        IStackFilter filter,
        int min,
        int max,
        OperationMode mode
    ) {
        if (level == null || pos == null || max <= 0 || min < 0 || min > max) return ItemStack.EMPTY;
        ItemTransferAccess access = PlatformStorage.itemTransfer(level, pos, side);
        if (access == null) return ItemStack.EMPTY;
        ItemMatcher matcher = stack -> filter == null || filter.matches(stack);

        ItemTransferResult probe;
        try (OperationScope scope = OperationScope.open(OperationMode.SIMULATE)) {
            probe = access.extract(matcher, max, scope);
        }
        if (probe.transferredCount() < min) return ItemStack.EMPTY;
        if (mode == OperationMode.SIMULATE) return probe.transferred();

        try (OperationScope scope = OperationScope.open(OperationMode.EXECUTE)) {
            ItemTransferResult result = access.extract(matcher, max, scope);
            if (result.transferredCount() < min) {
                scope.markFailed();
                return ItemStack.EMPTY;
            }
            return result.transferred();
        }
    }

    public static ItemStack insert(
        Level level,
        BlockPos pos,
        @Nullable BlockEntity tile,
        Direction side,
        ItemStack stack,
        @Nullable DyeColor colour,
        double speed,
        OperationMode mode
    ) {
        if (level == null || pos == null || stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemTransferAccess access = PlatformStorage.itemTransfer(level, pos, side);
        if (access == null) return stack;
        try (OperationScope scope = OperationScope.open(mode)) {
            ItemTransferResult result = access.insert(stack, scope);
            int remaining = Math.max(0, stack.getCount() - result.transferredCount());
            if (remaining == 0) return ItemStack.EMPTY;
            ItemStack remainder = stack.copy();
            remainder.setCount(remaining);
            return remainder;
        }
    }
}
