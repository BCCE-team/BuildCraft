package buildcraft.lib.platform.storage;
// Loader boundary owner: net.neoforged (position capability/injectable semantics).

import javax.annotation.Nullable;

import buildcraft.api.v2.OperationMode;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.inventory.ItemTransactorHelper;
import buildcraft.lib.inventory.NoSpaceInjectable;
import buildcraft.lib.inventory.NoSpaceTransactor;
import buildcraft.transport.internal.IInjectable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** NeoForge adapter for item-pipe endpoint discovery and legacy injectable compatibility. */
public final class PlatformItemPipeTransfer {
    private PlatformItemPipeTransfer() {}

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        if (level == null || pos == null) return false;
        if (ItemTransactorHelper.getInjectable(level, pos, side) != NoSpaceInjectable.INSTANCE) return true;
        if (ItemTransactorHelper.getTransactor(level, pos, side, tile) != NoSpaceTransactor.INSTANCE) return true;
        return PlatformStorage.itemTransfer(level, pos, side) != null;
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
        IItemTransactor transactor = ItemTransactorHelper.getTransactor(level, pos, side, tile);
        if (transactor == NoSpaceTransactor.INSTANCE) return ItemStack.EMPTY;
        return transactor.extract(filter, min, max, mode.isSimulation());
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
        ItemStack remainder = stack.copy();
        IInjectable injectable = ItemTransactorHelper.getInjectable(level, pos, side);
        if (injectable != NoSpaceInjectable.INSTANCE) {
            remainder = injectable.injectItem(
                remainder.copy(), mode == OperationMode.EXECUTE, side, colour, speed
            );
        }
        if (!remainder.isEmpty()) {
            IItemTransactor transactor = ItemTransactorHelper.getTransactor(level, pos, side, tile);
            if (transactor != NoSpaceTransactor.INSTANCE) {
                remainder = transactor.insert(remainder, false, mode.isSimulation());
            }
        }
        return remainder;
    }
}
