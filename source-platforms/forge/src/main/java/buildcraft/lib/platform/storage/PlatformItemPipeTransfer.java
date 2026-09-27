package buildcraft.lib.platform.storage;
// Loader boundary owner: net.minecraftforge (legacy capability/injectable semantics).

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

/** Forge adapter for item-pipe endpoint discovery and legacy injectable compatibility. */
public final class PlatformItemPipeTransfer {
    private PlatformItemPipeTransfer() {}

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        if (tile != null) {
            if (ItemTransactorHelper.getInjectable(tile, side) != NoSpaceInjectable.INSTANCE) return true;
            if (ItemTransactorHelper.getTransactor(tile, side) != NoSpaceTransactor.INSTANCE) return true;
        }
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
        if (max <= 0 || min < 0 || min > max || tile == null) return ItemStack.EMPTY;
        IItemTransactor transactor = ItemTransactorHelper.getTransactor(tile, side);
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
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remainder = stack.copy();
        if (tile != null) {
            IInjectable injectable = ItemTransactorHelper.getInjectable(tile, side);
            if (injectable != NoSpaceInjectable.INSTANCE) {
                remainder = injectable.injectItem(
                    remainder.copy(), mode == OperationMode.EXECUTE, side, colour, speed
                );
            }
            if (!remainder.isEmpty()) {
                IItemTransactor transactor = ItemTransactorHelper.getTransactor(tile, side);
                if (transactor != NoSpaceTransactor.INSTANCE) {
                    remainder = transactor.insert(remainder, false, mode.isSimulation());
                }
            }
        }
        return remainder;
    }
}
