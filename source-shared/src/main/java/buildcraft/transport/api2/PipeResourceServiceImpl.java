package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import buildcraft.api.v2.pipe.PipeFeTransferResult;
import buildcraft.api.v2.pipe.PipeFluidTransferResult;
import buildcraft.api.v2.pipe.PipeMjTransferResult;
import buildcraft.api.v2.pipe.PipeResourceService;
import buildcraft.api.v2.pipe.PipeResourceStatus;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import buildcraft.transport.tile.TilePipeHolder;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Executes resource operations against the existing fluid, MJ and FE pipe buffers. */
public final class PipeResourceServiceImpl implements PipeResourceService {
    public static final PipeResourceServiceImpl INSTANCE = new PipeResourceServiceImpl();
    private static final ResourceLocation REASON = Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:pipe_resource_transfer"));

    private PipeResourceServiceImpl() { }

    private record Pair(Pipe source, Pipe target, PipeResourceStatus status) { }

    private static Pair locate(Level sourceWorld, BlockPos source, Level targetWorld, BlockPos target) {
        if (sourceWorld.isClientSide() || targetWorld.isClientSide()
            || sourceWorld.getServer() == null || sourceWorld.getServer() != targetWorld.getServer()
            || !sourceWorld.getServer().isSameThread()) {
            return new Pair(null, null, PipeResourceStatus.SERVER_ONLY);
        }
        if (sourceWorld == targetWorld && source.equals(target)) {
            return new Pair(null, null, PipeResourceStatus.INVALID_TARGET);
        }
        if (!sourceWorld.hasChunkAt(source) || !targetWorld.hasChunkAt(target)) {
            return new Pair(null, null, PipeResourceStatus.UNLOADED);
        }
        BlockEntity sourceTile = sourceWorld.getBlockEntity(source);
        if (!(sourceTile instanceof TilePipeHolder sourceHolder)) {
            return new Pair(null, null, PipeResourceStatus.INVALID_SOURCE);
        }
        Pipe from = sourceHolder.getPipe();
        if (from == null || from == Pipe.EMPTY) {
            return new Pair(null, null, PipeResourceStatus.INVALID_SOURCE);
        }
        BlockEntity targetTile = targetWorld.getBlockEntity(target);
        if (!(targetTile instanceof TilePipeHolder targetHolder)) {
            return new Pair(null, null, PipeResourceStatus.INVALID_TARGET);
        }
        Pipe to = targetHolder.getPipe();
        if (to == null || to == Pipe.EMPTY) {
            return new Pair(null, null, PipeResourceStatus.INVALID_TARGET);
        }
        return new Pair(from, to, null);
    }

    private static PipeResourceStatus permission(Level sourceWorld, BlockPos source,
        Level targetWorld, BlockPos target, AutomationActor actor,
        WorldOperationKind sourceAction, WorldOperationKind targetAction, OperationMode mode) {
        try {
            if (BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
                actor, sourceWorld, source, WorldOperationTarget.block(source), sourceAction, mode, REASON
            )).verdict() == PermissionVerdict.DENY) return PipeResourceStatus.DENIED;
            if (BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
                actor, targetWorld, target, WorldOperationTarget.block(target), targetAction, mode, REASON
            )).verdict() == PermissionVerdict.DENY) return PipeResourceStatus.DENIED;
            return null;
        } catch (RuntimeException e) {
            BCLog.logger.warn("Pipe resource permission check failed at {}", source, e);
            return PipeResourceStatus.FAILED;
        }
    }

    private static PipeResourceStatus outcome(long requested, long actual, OperationMode mode) {
        if (actual == 0) return PipeResourceStatus.BLOCKED;
        if (mode == OperationMode.SIMULATE) return PipeResourceStatus.SIMULATED;
        return actual == requested ? PipeResourceStatus.MOVED : PipeResourceStatus.PARTIAL;
    }

    @Override
    public PipeFluidTransferResult transferFluid(Level sourceWorld, BlockPos source, Direction sourceSide,
        Level targetWorld, BlockPos target, Direction targetSide, FluidMatcher matcher,
        FluidAmount limit, AutomationActor actor, OperationMode mode) {
        Objects.requireNonNull(sourceWorld, "sourceWorld");
        Objects.requireNonNull(targetWorld, "targetWorld");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceSide, "sourceSide");
        Objects.requireNonNull(targetSide, "targetSide");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");
        if (limit.isZero()) return PipeFluidTransferResult.none(limit, PipeResourceStatus.BLOCKED);
        Pair pair = locate(sourceWorld, source, targetWorld, target);
        if (pair.status() != null) return PipeFluidTransferResult.none(limit, pair.status());
        if (!(pair.source().flow instanceof PipeFlowFluids from))
            return PipeFluidTransferResult.none(limit, PipeResourceStatus.INVALID_SOURCE);
        if (!(pair.target().flow instanceof PipeFlowFluids to))
            return PipeFluidTransferResult.none(limit, PipeResourceStatus.INVALID_TARGET);
        PipeResourceStatus verdict = permission(sourceWorld, source, targetWorld, target, actor,
            WorldOperationKind.FLUID_DRAIN, WorldOperationKind.FLUID_PLACE, mode);
        if (verdict != null) return PipeFluidTransferResult.none(limit, verdict);
        try {
            FluidVolume available = from.availableFluidForApi(sourceSide);
            if (available.isEmpty() || !matcher.matches(available.requireVariant(), FuelApiBridge.MATCH_CONTEXT))
                return PipeFluidTransferResult.none(limit, PipeResourceStatus.BLOCKED);
            int bound = (int) Math.min(Integer.MAX_VALUE, limit.milliBuckets());
            int moved = from.transferFluidForApi(to, sourceSide, targetSide, bound, mode == OperationMode.SIMULATE);
            if (moved > 0 && mode == OperationMode.EXECUTE) {
                pair.source().markChanged();
                pair.target().markChanged();
            }
            return new PipeFluidTransferResult(limit, available.withAmount(FluidAmount.of(moved)),
                outcome(limit.milliBuckets(), moved, mode));
        } catch (RuntimeException ex) {
            BCLog.logger.warn("Pipe fluid transfer failed at {}", source, ex);
            return PipeFluidTransferResult.none(limit, PipeResourceStatus.FAILED);
        }
    }

    @Override
    public PipeMjTransferResult transferMj(Level sourceWorld, BlockPos source, Direction sourceSide,
        Level targetWorld, BlockPos target, Direction targetSide,
        MjAmount limit, AutomationActor actor, OperationMode mode) {
        Objects.requireNonNull(sourceWorld, "sourceWorld");
        Objects.requireNonNull(targetWorld, "targetWorld");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceSide, "sourceSide");
        Objects.requireNonNull(targetSide, "targetSide");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");
        if (limit.isZero()) return PipeMjTransferResult.none(limit, PipeResourceStatus.BLOCKED);
        Pair pair = locate(sourceWorld, source, targetWorld, target);
        if (pair.status() != null) return PipeMjTransferResult.none(limit, pair.status());
        if (!(pair.source().flow instanceof PipeFlowPower from))
            return PipeMjTransferResult.none(limit, PipeResourceStatus.INVALID_SOURCE);
        if (!(pair.target().flow instanceof PipeFlowPower to))
            return PipeMjTransferResult.none(limit, PipeResourceStatus.INVALID_TARGET);
        PipeResourceStatus verdict = permission(sourceWorld, source, targetWorld, target, actor,
            WorldOperationKind.OTHER, WorldOperationKind.OTHER, mode);
        if (verdict != null) return PipeMjTransferResult.none(limit, verdict);
        try {
            long moved = from.transferPowerForApi(to, sourceSide, targetSide, limit.microMj(), mode == OperationMode.SIMULATE);
            if (moved > 0 && mode == OperationMode.EXECUTE) {
                pair.source().markChanged();
                pair.target().markChanged();
            }
            return new PipeMjTransferResult(limit, MjAmount.ofMicro(moved),
                outcome(limit.microMj(), moved, mode));
        } catch (RuntimeException ex) {
            BCLog.logger.warn("Pipe MJ transfer failed at {}", source, ex);
            return PipeMjTransferResult.none(limit, PipeResourceStatus.FAILED);
        }
    }

    @Override
    public PipeFeTransferResult transferExternalEnergy(Level sourceWorld, BlockPos source, Direction sourceSide,
        Level targetWorld, BlockPos target, Direction targetSide,
        long limit, AutomationActor actor, OperationMode mode) {
        Objects.requireNonNull(sourceWorld, "sourceWorld");
        Objects.requireNonNull(targetWorld, "targetWorld");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceSide, "sourceSide");
        Objects.requireNonNull(targetSide, "targetSide");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");
        if (limit < 0) throw new IllegalArgumentException("limit must be non-negative");
        if (limit == 0) return PipeFeTransferResult.none(limit, PipeResourceStatus.BLOCKED);
        Pair pair = locate(sourceWorld, source, targetWorld, target);
        if (pair.status() != null) return PipeFeTransferResult.none(limit, pair.status());
        if (!(pair.source().flow instanceof PipeFlowForgeEnergy from))
            return PipeFeTransferResult.none(limit, PipeResourceStatus.INVALID_SOURCE);
        if (!(pair.target().flow instanceof PipeFlowForgeEnergy to))
            return PipeFeTransferResult.none(limit, PipeResourceStatus.INVALID_TARGET);
        PipeResourceStatus verdict = permission(sourceWorld, source, targetWorld, target, actor,
            WorldOperationKind.OTHER, WorldOperationKind.OTHER, mode);
        if (verdict != null) return PipeFeTransferResult.none(limit, verdict);
        try {
            int bound = (int) Math.min(Integer.MAX_VALUE, limit);
            int moved = from.transferEnergyForApi(to, sourceSide, targetSide, bound, mode == OperationMode.SIMULATE);
            if (moved > 0 && mode == OperationMode.EXECUTE) {
                pair.source().markChanged();
                pair.target().markChanged();
            }
            return new PipeFeTransferResult(limit, moved, outcome(limit, moved, mode));
        } catch (RuntimeException ex) {
            BCLog.logger.warn("Pipe FE transfer failed at {}", source, ex);
            return PipeFeTransferResult.none(limit, PipeResourceStatus.FAILED);
        }
    }
}
