package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
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
import buildcraft.api.v2.robot.BlockRobotResource;
import buildcraft.api.v2.robot.BuildCraftDockPorts;
import buildcraft.api.v2.robot.DockPortType;
import buildcraft.api.v2.robot.RobotControl;
import buildcraft.api.v2.robot.RobotDock;
import buildcraft.api.v2.robot.RobotDockContext;
import buildcraft.api.v2.robot.RobotEventContext;
import buildcraft.api.v2.robot.RobotEventDecision;
import buildcraft.api.v2.robot.RobotEventListener;
import buildcraft.api.v2.robot.RobotHandle;
import buildcraft.api.v2.robot.RobotResource;
import buildcraft.api.v2.robot.RobotResourceLease;
import buildcraft.api.v2.robot.RobotResourceRequest;
import buildcraft.api.v2.robot.RobotResourceType;
import buildcraft.api.v2.robot.RobotService;
import buildcraft.api.v2.robot.RobotStatus;
import buildcraft.api.v2.robot.RobotTask;
import buildcraft.api.v2.robot.RobotTaskContext;
import buildcraft.api.v2.robot.RobotTaskResult;
import buildcraft.api.v2.robot.RobotTaskType;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.ResourceId;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationProvideItems;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.transport.internal.IInjectable;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Live API2 view over BuildCraft's persistent classic robotics registry. */
public final class RobotServiceImpl implements RobotService {
    public static RobotHandle view(EntityRobotBase robot) { return new RobotHandleAdapter(robot); }

    @Override
    public Optional<RobotHandle> robot(Level level, long id) {
        IRobotRegistry registry = registry(level);
        EntityRobotBase robot = registry == null ? null : registry.getLoadedRobot(id);
        return Optional.ofNullable(robot).map(RobotHandleAdapter::new);
    }

    @Override
    public Collection<? extends RobotHandle> robots(Level level) {
        IRobotRegistry registry = registry(level);
        if (registry == null) return List.of();
        List<RobotHandle> result = new ArrayList<>();
        for (EntityRobotBase robot : registry.getLoadedRobots()) {
            if (robot != null) result.add(new RobotHandleAdapter(robot));
        }
        result.sort(Comparator.comparingLong(RobotHandle::id));
        return List.copyOf(result);
    }

    @Override
    public Optional<RobotDock> dock(Level level, BlockPos pos) {
        IRobotRegistry registry = registry(level);
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.getStation(pos, null)).map(RobotDockAdapter::new);
    }

    @Override
    public Optional<RobotDock> dock(Level level, BlockPos pos, Direction side) {
        IRobotRegistry registry = registry(level);
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.getStation(pos, side)).map(RobotDockAdapter::new);
    }

    @Override
    public Optional<RobotResourceLease> acquire(Level level, long robotId, RobotResourceRequest request) {
        IRobotRegistry registry = registry(level);
        if (registry == null || request == null || registry.getLoadedRobot(robotId) == null) return Optional.empty();
        RobotResource resource = request.resource();
        RobotResourceType<?> type = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES).get(resource.typeId());
        return type == null ? Optional.empty() : acquireRegistered(type, level, robotId, resource, request.amount());
    }

    private static <R extends RobotResource> Optional<RobotResourceLease> acquireRegistered(
        RobotResourceType<R> type, Level level, long robotId, RobotResource resource, long amount
    ) {
        if (!type.resourceType().isInstance(resource)) return Optional.empty();
        return type.acquirer().acquire(level, robotId, type.resourceType().cast(resource), amount);
    }

    static Optional<RobotResourceLease> acquireBlockResource(Level level, long robotId, BlockRobotResource resource, long amount) {
        IRobotRegistry registry = registry(level);
        if (registry == null || registry.getLoadedRobot(robotId) == null || amount != 1) return Optional.empty();
        ResourceIdBlock legacy = new ResourceIdBlock(resource.position());
        legacy.side = resource.side().orElse(null);
        if (!registry.take(legacy, robotId)) return Optional.empty();
        return Optional.of(new Lease(registry, robotId, resource, legacy));
    }

    @Override
    public RobotEventDecision evaluateEvent(RobotEventContext context) {
        RobotEventDecision decision = RobotEventDecision.PASS;
        var entries = new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.ROBOT_EVENT_LISTENERS).entries());
        entries.sort(Comparator.comparing(entry -> entry.id().toString()));
        for (var entry : entries) {
            RobotEventListener listener = entry.value();
            RobotEventDecision next = listener.onRobotEvent(context);
            if (next != null) decision = decision.merge(next);
            if (decision.isTerminal()) break;
        }
        return decision;
    }

    private static IRobotRegistry registry(Level level) {
        return level == null || RobotManager.registryProvider == null ? null : RobotManager.registryProvider.getRegistry(level);
    }


    private static AutomationActor actor(EntityRobotBase robot) {
        if (robot instanceof EntityRobot entity) {
            GameProfile owner = entity.getOwnerProfile();
            if (owner != null && owner.getId() != null && !owner.getId().equals(FakePlayerProvider.NULL_PROFILE.getId())) {
                return AutomationActor.machineOwner(owner.getId(), owner.getName(), Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:robot")));
            }
        }
        return AutomationActor.system(Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:robot")));
    }

    private static final class RobotHandleAdapter implements RobotHandle {
        private final EntityRobotBase robot;
        private final RobotControl control = new Control();

        private RobotHandleAdapter(EntityRobotBase robot) { this.robot = robot; }

        @Override public long id() { return robot.getRobotId(); }

        @Override
        public Optional<UUID> owner() {
            AutomationActor actor = actor(robot);
            return actor.playerId();
        }

        @Override public BlockPos blockPosition() { return robot.blockPosition().immutable(); }

        @Override
        public RobotStatus status() {
            if (!robot.isAlive()) return RobotStatus.ERROR;
            if (robot.getDockingStation() != null) return RobotStatus.DOCKED;
            if (robot.isMoving()) return RobotStatus.TRAVELLING;
            if (robot instanceof EntityRobot entity && entity.isAsleepForRendering()) return RobotStatus.IDLE;
            return RobotStatus.WORKING;
        }

        @Override
        public Optional<ResourceLocation> currentTaskType() {
            AIRobot active = robot.getActiveAI();
            if (active == null) return Optional.empty();
            String name = RobotManager.getAIRobotName(active.getClass());
            if (name == null || name.isBlank()) return Optional.empty();
            String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
            return Optional.of(Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:classic_robot_task/" + safe)));
        }

        @Override public Optional<RobotControl> control() { return Optional.of(control); }

        private final class Control implements RobotControl {
            @Override
            public boolean moveTo(BlockPos target, OperationMode mode) {
                if (target == null || !robot.isAlive()) return false;
                if (mode == OperationMode.EXECUTE) {
                    robot.setMainAIOverride(new AIRobotGotoBlock(robot, target.getX(), target.getY(), target.getZ()));
                }
                return true;
            }

            @Override
            public boolean assign(RobotTask task, OperationMode mode) {
                if (task == null || !robot.isAlive()) return false;
                RobotTaskType<?> type = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES).get(task.typeId());
                if (type == null || !type.taskType().isInstance(task)) return false;
                if (mode == OperationMode.EXECUTE) robot.setMainAIOverride(new ApiTaskAI(robot, task, RobotHandleAdapter.this));
                return true;
            }

            @Override
            public boolean cancelTask(OperationMode mode) {
                if (!robot.isAlive()) return false;
                if (mode == OperationMode.EXECUTE) robot.setMainAIOverride(null);
                return true;
            }
        }
    }

    private static final class ApiTaskAI extends AIRobot {
        private final RobotTask task;
        private final RobotHandle handle;

        private ApiTaskAI(EntityRobotBase robot, RobotTask task, RobotHandle handle) {
            super(robot);
            this.task = task;
            this.handle = handle;
        }

        @Override
        public void update() {
            RobotTaskResult result = task.tick(new RobotTaskContext(robot.level(), handle, actor(robot)));
            if (result == null) {
                setSuccess(false);
                terminate();
                return;
            }
            switch (result.status()) {
                case RUNNING, RETRY -> { }
                case COMPLETE -> terminate();
                case FAILED -> { setSuccess(false); terminate(); }
            }
        }
    }

    private record RobotDockAdapter(DockingStation station) implements RobotDock {
        @Override public BlockPos position() { return station.index().toBlockPos().immutable(); }
        @Override public Optional<Direction> side() { return Optional.ofNullable(station.side()); }

        @Override
        public <T> Optional<T> port(DockPortType<T> requestedType) {
            DockPortType<?> registered = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_DOCK_PORT_TYPES).get(requestedType.id());
            if (registered == null || !registered.portType().equals(requestedType.portType())) return Optional.empty();
            return portRegistered(castPortType(registered));
        }

        @SuppressWarnings("unchecked")
        private static <T> DockPortType<T> castPortType(DockPortType<?> type) {
            return (DockPortType<T>) type;
        }

        private <T> Optional<T> portRegistered(DockPortType<T> type) {
            if (BuildCraftDockPorts.ITEMS.id().equals(type.id())
                    && (station.getItemOutput() != null || station.getItemInput() != null)) {
                return Optional.of(type.portType().cast(new DockItemPort(station)));
            }
            if (BuildCraftDockPorts.FLUIDS.id().equals(type.id())
                    && (station.getFluidOutput() != null || station.getFluidInput() != null)) {
                return Optional.of(type.portType().cast(new DockFluidPort(station)));
            }
            Level level = station.level();
            if (level == null) return Optional.empty();
            return type.resolve(new RobotDockContext(level, position(), side(), occupied()));
        }

        @Override public boolean occupied() { return station.isTaken(); }
    }

    /** Public item view over the classic station load/unload endpoints. */
    private record DockItemPort(DockingStation station) implements ItemPort {
        @Override
        public ItemTransferResult insert(ItemStack offered, OperationMode mode) {
            if (offered == null || offered.isEmpty()) return ItemTransferResult.nothing(offered == null ? 0 : offered.getCount());
            IInjectable output = station.getItemOutput();
            Direction side = station.getItemOutputSide();
            if (output == null || side == null || !output.canInjectItems(side)) return ItemTransferResult.nothing(offered.getCount());
            ItemStack remainder = output.injectItem(offered.copy(), mode == OperationMode.EXECUTE, side, null, 0.08D);
            int accepted = Math.max(0, offered.getCount() - (remainder == null ? offered.getCount() : remainder.getCount()));
            return ItemTransferResult.ofInsertion(offered, Math.min(offered.getCount(), accepted));
        }

        @Override
        public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationMode mode) {
            if (matcher == null || maxCount <= 0) return ItemTransferResult.nothing(Math.max(0, maxCount));
            Container input = station.getItemInput();
            if (input == null) return ItemTransferResult.nothing(maxCount);
            Direction side = station.getItemInputSide();
            for (int slot : accessibleSlots(input, side)) {
                if (slot < 0 || slot >= input.getContainerSize()) continue;
                ItemStack stack = input.getItem(slot);
                if (stack == null || stack.isEmpty() || !matcher.matches(stack)) continue;
                if (!canTake(input, slot, stack, side) || !stationAllowsExtraction(stack, matcher)) continue;

                int amount = Math.min(maxCount, stack.getCount());
                ItemStack moved;
                if (mode == OperationMode.EXECUTE) {
                    moved = input.removeItem(slot, amount);
                    if (moved.isEmpty()) continue;
                    input.setChanged();
                } else {
                    moved = stack.copy();
                    moved.setCount(amount);
                }
                return ItemTransferResult.ofExtraction(maxCount, moved);
            }
            return ItemTransferResult.nothing(maxCount);
        }

        private int[] accessibleSlots(Container input, Direction side) {
            if (input instanceof WorldlyContainer sided && side != null) {
                return sided.getSlotsForFace(side);
            }
            int[] slots = new int[input.getContainerSize()];
            for (int i = 0; i < slots.length; i++) slots[i] = i;
            return slots;
        }

        private boolean canTake(Container input, int slot, ItemStack stack, Direction side) {
            return !(input instanceof WorldlyContainer sided) || side == null
                || sided.canTakeItemThroughFace(slot, stack, side);
        }

        private boolean stationAllowsExtraction(ItemStack stack, ItemMatcher matcher) {
            return ActionStationProvideItems.canExtractItem(station, stack)
                && ActionRobotFilter.canInteractWithItem(station, matcher::matches, ActionStationProvideItems.class);
        }
    }

    /** Loader-neutral fluid view over the classic station load/unload endpoints. */
    private record DockFluidPort(DockingStation station) implements FluidPort {
        @Override
        public FluidTransferResult insert(FluidVolume offered, OperationMode mode) {
            if (offered == null || offered.isEmpty()) return FluidTransferResult.nothing(offered == null ? FluidAmount.ZERO : offered.amount());
            FluidStorage<FluidStack> output = station.getFluidOutput();
            if (output == null) return FluidTransferResult.nothing(offered.amount());
            FluidStack stack = FuelApiBridge.stackOf(offered);
            if (stack.isEmpty()) return FluidTransferResult.nothing(offered.amount());
            int accepted = output.fill(stack, mode == OperationMode.SIMULATE);
            return FluidTransferResult.ofInsertion(offered, FluidAmount.of(Math.max(0, accepted)));
        }

        @Override
        public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationMode mode) {
            if (matcher == null || maxAmount == null || maxAmount.isZero()) {
                return FluidTransferResult.nothing(maxAmount == null ? FluidAmount.ZERO : maxAmount);
            }
            FluidStorage<FluidStack> input = station.getFluidInput();
            if (input == null) return FluidTransferResult.nothing(maxAmount);
            int limit = (int) Math.min(Integer.MAX_VALUE, maxAmount.milliBuckets());
            for (int tank = 0; tank < input.getTanks(); tank++) {
                FluidStack stored = input.getFluidInTank(tank);
                if (stored == null || stored.isEmpty()) continue;
                if (!matcher.matches(FuelApiBridge.variantOf(stored), FuelApiBridge.MATCH_CONTEXT)) continue;
                FluidStack requested = stored.copy();
                requested.setAmount(Math.min(limit, stored.getAmount()));
                FluidStack drained = input.drain(requested, mode == OperationMode.SIMULATE);
                return FluidTransferResult.ofExtraction(maxAmount, FuelApiBridge.volumeOf(drained));
            }
            return FluidTransferResult.nothing(maxAmount);
        }
    }

    private static final class Lease implements RobotResourceLease {
        private final IRobotRegistry registry;
        private final long robotId;
        private final RobotResource resource;
        private final ResourceId legacy;
        private boolean active = true;

        private Lease(IRobotRegistry registry, long robotId, RobotResource resource, ResourceId legacy) {
            this.registry = registry; this.robotId = robotId; this.resource = resource; this.legacy = legacy;
        }
        @Override public long robotId() { return robotId; }
        @Override public RobotResource resource() { return resource; }
        @Override public long amount() { return 1; }
        @Override public boolean active() { return active; }
        @Override public void close() { if (active) { registry.release(legacy); active = false; } }
    }
}
