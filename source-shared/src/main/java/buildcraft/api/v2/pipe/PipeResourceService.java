package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.permission.AutomationActor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Resource transfer between two already-loaded BuildCraft pipes on the same server.
 * Source and destination are validated before mutation. Transfers may be partial and
 * consume only the actual accepted amount. SIMULATE never changes resource buffers.
 * No chunks are force-loaded. Operations run on the owning server thread.
 *
 * A completed transfer is atomic in memory; separate chunk/world disk saves are not
 * a distributed transaction. Applications requiring crash-proof cross-world transfers
 * must provide their own durable journal and replay protocol.
 */
public interface PipeResourceService {
    PipeFluidTransferResult transferFluid(Level sourceWorld, BlockPos source, Direction sourceSide,
        Level targetWorld, BlockPos target, Direction targetSide, FluidMatcher matcher,
        FluidAmount limit, AutomationActor actor, OperationMode mode);

    PipeMjTransferResult transferMj(Level sourceWorld, BlockPos source, Direction sourceSide,
        Level targetWorld, BlockPos target, Direction targetSide,
        MjAmount limit, AutomationActor actor, OperationMode mode);

    PipeFeTransferResult transferExternalEnergy(Level sourceWorld, BlockPos source, Direction sourceSide,
        Level targetWorld, BlockPos target, Direction targetSide,
        long limit, AutomationActor actor, OperationMode mode);
}
