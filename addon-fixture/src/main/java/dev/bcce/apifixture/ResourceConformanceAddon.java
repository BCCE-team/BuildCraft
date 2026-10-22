package dev.bcce.apifixture;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.pipe.PipeFeTransferResult;
import buildcraft.api.v2.pipe.PipeFluidTransferResult;
import buildcraft.api.v2.pipe.PipeMjTransferResult;
import buildcraft.api.v2.pipe.PipeResourceService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Exercises all three public resource transfer channels without accessing pipe internals. */
public final class ResourceConformanceAddon {
    private ResourceConformanceAddon() { }

    public static PipeFluidTransferResult fluid(PipeResourceService service,
        Level fromWorld, BlockPos from, Direction fromSide,
        Level toWorld, BlockPos to, Direction toSide,
        FluidMatcher matcher, FluidAmount limit, AutomationActor actor, OperationMode mode) {
        return service.transferFluid(fromWorld, from, fromSide, toWorld, to, toSide, matcher, limit, actor, mode);
    }

    public static PipeMjTransferResult mj(PipeResourceService service,
        Level fromWorld, BlockPos from, Direction fromSide,
        Level toWorld, BlockPos to, Direction toSide,
        MjAmount limit, AutomationActor actor, OperationMode mode) {
        return service.transferMj(fromWorld, from, fromSide, toWorld, to, toSide, limit, actor, mode);
    }

    public static PipeFeTransferResult fe(PipeResourceService service,
        Level fromWorld, BlockPos from, Direction fromSide,
        Level toWorld, BlockPos to, Direction toSide,
        long limit, AutomationActor actor, OperationMode mode) {
        return service.transferExternalEnergy(fromWorld, from, fromSide, toWorld, to, toSide, limit, actor, mode);
    }
}
