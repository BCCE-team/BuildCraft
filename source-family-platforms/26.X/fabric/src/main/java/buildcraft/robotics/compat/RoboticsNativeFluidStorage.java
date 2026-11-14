/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.compat;

import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import buildcraft.lib.platform.storage.FluidStorage;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * One boundary between arbitrary Fabric fluid Storage and legacy robot station AI.
 * Amounts and the original Data Components are preserved in millibuckets.  A
 * simulation creates a nested Fabric transaction and closes without committing.
 */
public final class RoboticsNativeFluidStorage implements FluidStorage<FluidStack> {
    private static final long UNIT = FluidConstants.BUCKET / 1000L;
    private final Storage<FluidVariant> nativeStorage;
    private RoboticsNativeFluidStorage(Storage<FluidVariant> storage) { nativeStorage = storage; }

    @Nullable
    public static RoboticsNativeFluidStorage lookup(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        Storage<FluidVariant> found = net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(
            level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
        return found == null ? null : new RoboticsNativeFluidStorage(found);
    }
    private static int limited(long amount) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, amount));
    }
    private static FluidVariant variant(FluidStack stack) {
        return stack == null || stack.isEmpty() ? FluidVariant.blank()
            : FluidVariant.of(stack.getFluid(), stack.getComponentsPatch());
    }
    private static FluidStack stack(FluidVariant variant, long amount) {
        return variant.isBlank() || amount < UNIT ? FluidStack.EMPTY
            : new FluidStack(variant.getFluid(), limited(amount / UNIT), variant.getComponentsPatch());
    }
    private List<StorageView<FluidVariant>> views() {
        List<StorageView<FluidVariant>> found = new ArrayList<>();
        for (StorageView<FluidVariant> view : nativeStorage) found.add(view);
        return found;
    }
    public int getTanks() { return views().size(); }
    public FluidStack getFluidInTank(int tank) {
        List<StorageView<FluidVariant>> views = views();
        return tank < 0 || tank >= views.size() ? FluidStack.EMPTY :
            stack(views.get(tank).getResource(), views.get(tank).getAmount());
    }
    public int getTankCapacity(int tank) {
        List<StorageView<FluidVariant>> views = views();
        return tank < 0 || tank >= views.size() ? 0 : limited(views.get(tank).getCapacity() / UNIT);
    }
    public boolean isFluidValid(int tank, FluidStack offered) {
        if (offered == null || offered.isEmpty()) return false;
        try (Transaction nested = FabricTransactionScope.begin()) {
            List<StorageView<FluidVariant>> views = views();
            return tank >= 0 && tank < views.size()
                && nativeStorage.insert(variant(offered), UNIT, nested) >= UNIT;
        }
    }
    public int fill(FluidStack offered, boolean simulate) {
        if (offered == null || offered.isEmpty()) return 0;
        try (Transaction tx = FabricTransactionScope.begin()) {
            long moved = nativeStorage.insert(variant(offered), (long)offered.getAmount() * UNIT, tx);
            if (moved % UNIT != 0) return 0; // Do not commit a fractional millibucket.
            if (!simulate) tx.commit();
            return limited(moved / UNIT);
        }
    }
    public FluidStack drain(FluidStack offered, boolean simulate) {
        if (offered == null || offered.isEmpty()) return FluidStack.EMPTY;
        try (Transaction tx = FabricTransactionScope.begin()) {
            long moved = nativeStorage.extract(variant(offered), (long)offered.getAmount() * UNIT, tx);
            if (moved % UNIT != 0) return FluidStack.EMPTY;
            if (!simulate) tx.commit();
            return offered.copyWithAmount(limited(moved / UNIT));
        }
    }
    public FluidStack drain(int max, boolean simulate) {
        if (max <= 0) return FluidStack.EMPTY;
        for (StorageView<FluidVariant> view : nativeStorage) {
            if (view.isResourceBlank()) continue;
            FluidVariant variant = view.getResource();
            try (Transaction tx = FabricTransactionScope.begin()) {
                long moved = nativeStorage.extract(variant, (long)max * UNIT, tx);
                if (moved < UNIT || moved % UNIT != 0) continue;
                if (!simulate) tx.commit();
                return stack(variant, moved);
            }
        }
        return FluidStack.EMPTY;
    }
}
