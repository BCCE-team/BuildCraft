/* Fabric 26.3 permission-aware liquid placement. MPL-2.0. */
package buildcraft.factory.compat;

import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Converts one BuildCraft bucket (1,000 mB) to a source block. Unlike the
 * NeoForge ResourceHandler transaction, vanilla Fabric world placement and
 * tank extraction do not share a transaction; validate all conditions first,
 * place with the owner actor, then debit the tank only after placement succeeds.
 */
public final class FabricFactoryFluidPlacement {
    public static final int BUCKET_MB = 1000;
    private FabricFactoryFluidPlacement() {}

    public static boolean placeBucket(Tank tank, ServerLevel level, BlockPos pos, Player actor) {
        if (tank == null || level == null || pos == null || actor == null
            || !level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)
            || !level.getWorldBorder().isWithinBounds(pos)) return false;

        FluidStack available = tank.drainInternal(BUCKET_MB, FluidAction.SIMULATE);
        if (available.isEmpty() || available.getAmount() != BUCKET_MB) return false;
        // Data-component payloads have no block-state representation. Reject
        // rather than silently discarding metadata on placement.
        if (!available.getComponentsPatch().isEmpty()) return false;

        Fluid fluid = available.getFluid();
        if (fluid instanceof FlowingFluid flowing) fluid = flowing.getSource();
        if (fluid == Fluids.EMPTY || fluid == null) return false;

        BlockState before = level.getBlockState(pos);
        if (!before.isAir()) {
            // The Flood Gate can refill the same flowing liquid, but cannot
            // overwrite solid blocks, containers or source blocks.
            if (before.getFluidState().isEmpty() || before.getFluidState().isSource()
                || !before.getFluidState().getType().isSame(fluid)) return false;
        }

        BlockState placed = fluid.defaultFluidState().createLegacyBlock();
        if (placed.isAir() || placed.getFluidState().isEmpty() || placed.equals(before)) return false;
        if (!BlockUtil.placeBlock(level, pos, placed, actor, Direction.UP, 3)) return false;

        FluidStack drained = tank.drainInternal(available, FluidAction.EXECUTE);
        if (drained.getAmount() != BUCKET_MB) {
            // A re-entrant placement callback may have changed the tank. Keep
            // the world and stored liquid consistent when extraction fails.
            level.setBlock(pos, before, 3);
            return false;
        }
        return true;
    }
}
