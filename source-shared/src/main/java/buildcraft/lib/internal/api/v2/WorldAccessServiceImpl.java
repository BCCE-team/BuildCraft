package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.platform.PlatformServices;
import buildcraft.api.v2.world.WorldAccessContext;
import buildcraft.api.v2.world.WorldAccessService;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** All world reads and borrowed ports share the same unload/thread/permission guards. */
public final class WorldAccessServiceImpl implements WorldAccessService {
    private static final ResourceLocation REASON = Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:world_access"));
    private static final ResourceLocation GUARD = Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:world_access_guard"));

    @Override public WorldAccessContext open(Level level, BlockPos origin, AutomationActor actor) {
        return scoped(level, origin, actor, () -> true);
    }

    @Override public WorldAccessContext scoped(Level level, BlockPos origin, AutomationActor actor, BooleanSupplier valid) {
        return new Bound(level, origin, actor, valid);
    }

    private static final class Bound implements WorldAccessContext {
        private final Level level;
        private final BlockPos origin;
        private final AutomationActor actor;
        private final BooleanSupplier valid;
        private Bound(Level level, BlockPos origin, AutomationActor actor, BooleanSupplier valid) {
            this.level = Objects.requireNonNull(level, "level");
            this.origin = Objects.requireNonNull(origin, "origin").immutable();
            this.actor = Objects.requireNonNull(actor, "actor");
            this.valid = Objects.requireNonNull(valid, "valid");
        }

        @Override public BlockPos origin() { return origin; }
        @Override public AutomationActor actor() { return actor; }
        private boolean alive() { return valid.getAsBoolean(); }
        private boolean onThread() {
            return !level.isClientSide() && level.getServer() != null && level.getServer().isSameThread();
        }
        @Override public boolean isServerSide() { return alive() && onThread(); }
        @Override public long gameTime() { return alive() && onThread() ? level.getGameTime() : -1L; }
        @Override public boolean isLoaded(BlockPos pos) {
            Objects.requireNonNull(pos, "pos");
            return alive() && onThread() && level.hasChunkAt(pos);
        }
        @Override public Optional<BlockState> blockState(BlockPos pos) {
            return isLoaded(pos) ? Optional.of(level.getBlockState(pos)) : Optional.empty();
        }
        @Override public Optional<ResourceLocation> pipeTypeId(BlockPos pos) {
            return loadedPipe(pos).map(PipeView::typeId);
        }
        @Override public Optional<java.util.Set<Direction>> pipeConnections(BlockPos pos) {
            return loadedPipe(pos).map(pipe -> java.util.Set.copyOf(pipe.connectedSides()));
        }
        private Optional<PipeView> loadedPipe(BlockPos pos) {
            return isLoaded(pos) ? BuildCraftApi.service(BuildCraftServices.PIPES).pipe(level, pos) : Optional.empty();
        }
        @Override public PermissionDecision permission(BlockPos pos, WorldOperationKind operation, OperationMode mode) {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(mode, "mode");
            if (!isLoaded(pos)) return PermissionDecision.deny(GUARD, "unloaded_or_outside_callback_or_thread");
            try {
                return BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
                    actor, level, origin, WorldOperationTarget.block(pos), operation, mode, REASON));
            } catch (RuntimeException ex) {
                // A broken protection provider must not turn into permission to operate on a claim.
                return PermissionDecision.deny(GUARD, "permission_provider_failed");
            }
        }
        private boolean allowed(BlockPos pos, WorldOperationKind operation, OperationMode mode) {
            return permission(pos, operation, mode).verdict() != PermissionVerdict.DENY;
        }
        private boolean allowedItem(BlockPos pos, OperationMode mode) {
            return allowed(pos, WorldOperationKind.INVENTORY_ACCESS, mode);
        }
        @Override public Optional<ItemPort> itemPort(BlockPos pos, Direction side) {
            Objects.requireNonNull(side, "side");
            if (!allowedItem(pos, OperationMode.SIMULATE)) return Optional.empty();
            Optional<PlatformServices> platform = BuildCraftApi.runtime().service(BuildCraftServices.PLATFORM);
            if (platform.isEmpty()) return Optional.empty();
            Optional<ItemPort> found = platform.get().itemTransfer().flatMap(bridge -> bridge.find(level, pos, side));
            return found.map(delegate -> new ItemPort() {
                @Override public ItemTransferResult insert(net.minecraft.world.item.ItemStack stack, OperationMode mode) {
                    Objects.requireNonNull(stack, "stack"); Objects.requireNonNull(mode, "mode");
                    return allowedItem(pos, mode) ? delegate.insert(stack.copy(), mode) : ItemTransferResult.nothing(stack.getCount());
                }
                @Override public ItemTransferResult extract(ItemMatcher matcher, int count, OperationMode mode) {
                    Objects.requireNonNull(matcher, "matcher"); Objects.requireNonNull(mode, "mode");
                    return allowedItem(pos, mode) ? delegate.extract(matcher, count, mode) : ItemTransferResult.nothing(count);
                }
            });
        }
        @Override public Optional<FluidPort> fluidPort(BlockPos pos, Direction side) {
            Objects.requireNonNull(side, "side");
            if (!allowed(pos, WorldOperationKind.FLUID_DRAIN, OperationMode.SIMULATE)
                && !allowed(pos, WorldOperationKind.FLUID_PLACE, OperationMode.SIMULATE)) return Optional.empty();
            Optional<PlatformServices> platform = BuildCraftApi.runtime().service(BuildCraftServices.PLATFORM);
            if (platform.isEmpty()) return Optional.empty();
            Optional<FluidPort> found = platform.get().fluidTransfer().flatMap(bridge -> bridge.find(level, pos, side));
            return found.map(delegate -> new FluidPort() {
                @Override public FluidTransferResult insert(FluidVolume volume, OperationMode mode) {
                    Objects.requireNonNull(volume, "volume"); Objects.requireNonNull(mode, "mode");
                    return allowed(pos, WorldOperationKind.FLUID_PLACE, mode) ? delegate.insert(volume, mode)
                        : FluidTransferResult.nothing(volume.amount());
                }
                @Override public FluidTransferResult extract(FluidMatcher matcher, FluidAmount max, OperationMode mode) {
                    Objects.requireNonNull(matcher, "matcher"); Objects.requireNonNull(max, "max"); Objects.requireNonNull(mode, "mode");
                    return allowed(pos, WorldOperationKind.FLUID_DRAIN, mode) ? delegate.extract(matcher, max, mode)
                        : FluidTransferResult.nothing(max);
                }
            });
        }
        @Override public Optional<ExternalEnergyPort> energyPort(BlockPos pos, Direction side) {
            Objects.requireNonNull(side, "side");
            if (!allowed(pos, WorldOperationKind.INVENTORY_ACCESS, OperationMode.SIMULATE)) return Optional.empty();
            Optional<PlatformServices> platform = BuildCraftApi.runtime().service(BuildCraftServices.PLATFORM);
            if (platform.isEmpty()) return Optional.empty();
            Optional<ExternalEnergyPort> found = platform.get().energyTransfer().flatMap(bridge -> bridge.find(level, pos, side));
            return found.map(delegate -> new ExternalEnergyPort() {
                @Override public long insert(long offered, OperationMode mode) {
                    Objects.requireNonNull(mode, "mode");
                    return allowedItem(pos, mode) ? delegate.insert(offered, mode) : 0;
                }
                @Override public long extract(long requested, OperationMode mode) {
                    Objects.requireNonNull(mode, "mode");
                    return allowedItem(pos, mode) ? delegate.extract(requested, mode) : 0;
                }
                @Override public long stored() { return allowedItem(pos, OperationMode.SIMULATE) ? delegate.stored() : 0; }
                @Override public long capacity() { return allowedItem(pos, OperationMode.SIMULATE) ? delegate.capacity() : 0; }
                @Override public boolean canInsert() { return allowedItem(pos, OperationMode.SIMULATE) && delegate.canInsert(); }
                @Override public boolean canExtract() { return allowedItem(pos, OperationMode.SIMULATE) && delegate.canExtract(); }
            });
        }
    }
}
