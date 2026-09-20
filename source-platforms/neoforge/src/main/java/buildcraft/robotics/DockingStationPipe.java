package buildcraft.robotics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.request.ItemRequest;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.robotics.internal.api2.RequestSupport;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import buildcraft.compat.CompatCapTransfromer;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import buildcraft.robotics.statements.ActionStationRequestItems;
import buildcraft.robotics.plug.RobotStationPluggable;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.jetbrains.annotations.NotNull;

public class DockingStationPipe extends DockingStation implements RequestProvider {
    private static final long INVALID_STATION_GRACE_TICKS = 20L;

    private TilePipeHolder pipe;
    private boolean removingInvalidStation;
    private long invalidSinceTick = Long.MIN_VALUE;

    private final IInjectable injectablePipe = new IInjectable() {
        @Override
        public boolean canInjectItems(Direction from) {
            return getPipe() != null && getPipe().getPipe() != null
                    && getPipe().getPipe().flow instanceof PipeFlowItems
                    && normalizeOutputSide(from) != null;
        }

        @Override
        public ItemStack injectItem(ItemStack stack, boolean doAdd, Direction from, DyeColor color, double speed) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (getPipe() == null || !(getPipe().getPipe().flow instanceof PipeFlowItems items)) {
                return stack;
            }
            Direction outputSide = normalizeOutputSide(from);
            if (outputSide == null) {
                return stack;
            }
            if (doAdd && items.doesContainItems()) {
                return stack;
            }
            // In PipeFlowItems the "from" side is the side the stack came from and it is excluded from routing when
            // the item reaches the pipe centre. The robot station sits on side(), so use that side here. Normal pipe
            // insertion events run and the real remainder is returned, so the robot removes only accepted items.
            return items.injectItemFromRobotStation(stack.copy(), doAdd, outputSide, color, speed);
        }
    };

    private final IFluidHandler injectableFluidPipe = new IFluidHandler() {
        @Override
        public int getTanks() {
            return 0;
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return 0;
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return canFillFluid(stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty() || getPipe() == null || getPipe().getPipe() == null
                    || !(getPipe().getPipe().flow instanceof PipeFlowFluids fluids)) {
                return 0;
            }
            return fluids.insertFluidsForce(resource.copy(), normalizeOutputSide(side()), action);
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }

        private boolean canFillFluid(FluidStack stack) {
            if (stack.isEmpty() || getPipe() == null || getPipe().getPipe() == null
                    || !(getPipe().getPipe().flow instanceof PipeFlowFluids fluids)) {
                return false;
            }
            return fluids.insertFluidsForce(stack.copy(), normalizeOutputSide(side()), FluidAction.SIMULATE) > 0;
        }
    };

    public DockingStationPipe() {
    }

    public DockingStationPipe(TilePipeHolder pipe, Direction side) {
        super(new BlockIndex(pipe.getPipePos()), side);
        this.pipe = pipe;
        setLevel(pipe.getPipeWorld());
    }

    public TilePipeHolder getPipe() {
        Level stationLevel = level();
        if (stationLevel == null) {
            return pipe != null && !pipe.isRemoved() ? pipe : null;
        }

        BlockPos pos = new BlockPos(x(), y(), z());

        // A station whose chunk is merely unloaded must stay in the robotics registry so the robot can rebind by id
        // when both sides load again. Only validate and remove the station if the chunk is loaded.
        if (!stationLevel.isLoaded(pos)) {
            return pipe;
        }

        BlockEntity blockEntity = stationLevel.getBlockEntity(pos);
        if (blockEntity instanceof TilePipeHolder holder
                && !holder.isRemoved()
                && holder.getPipe() != null
                && holder.getPipe() != Pipe.EMPTY
                && side() != null
                && holder.getPluggable(side()) instanceof RobotStationPluggable) {
            pipe = holder;
            invalidSinceTick = Long.MIN_VALUE;
            return pipe;
        }

        // During chunk loading the block state may already be present while its TilePipeHolder/pluggables are not yet
        // fully restored. Removing the persistent station immediately in that window is what detached some robots.
        // Require a full second of continuously invalid loaded state before treating the station as actually removed.
        pipe = null;
        long gameTime = stationLevel.getGameTime();
        if (invalidSinceTick == Long.MIN_VALUE) {
            invalidSinceTick = gameTime;
        } else if (gameTime - invalidSinceTick >= INVALID_STATION_GRACE_TICKS) {
            removeInvalidStation(stationLevel);
        }
        return null;
    }

    private void removeInvalidStation(Level stationLevel) {
        if (stationLevel == null || stationLevel.isClientSide || RobotManager.registryProvider == null || removingInvalidStation) {
            return;
        }

        removingInvalidStation = true;
        try {
            RobotManager.registryProvider.getRegistry(stationLevel).removeStation(this);
        } finally {
            removingInvalidStation = false;
        }
    }

    private void scheduleRenderUpdateIfLoaded() {
        if (pipe != null && !pipe.isRemoved() && pipe.getLevel() != null) {
            pipe.scheduleRenderUpdate();
        }
    }

    private Direction normalizeOutputSide(Direction from) {
        return side() != null ? side() : from;
    }

    @Override
    public Iterable<StatementSlot> getActiveActions() {
        if (getPipe() == null) {
            return Collections.emptyList();
        }

        List<StatementSlot> actions = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            if (getPipe().getPluggable(direction) instanceof PluggableGate gate) {
                actions.addAll(gate.logic.getActiveActions());
            }
        }
        return actions;
    }

    @Override
    public boolean isInitialized() {
        return getPipe() != null && getPipe().getPipe() != null;
    }

    @Override
    public boolean take(EntityRobotBase robot) {
        if (getPipe() == null) return false;
        boolean result = super.take(robot);
        if (result) {
            pipe.scheduleRenderUpdate();
        }
        return result;
    }

    @Override
    public boolean takeAsMain(EntityRobotBase robot) {
        if (getPipe() == null) return false;
        boolean result = super.takeAsMain(robot);
        if (result) {
            pipe.scheduleRenderUpdate();
        }
        return result;
    }

    @Override
    public void unsafeRelease(EntityRobotBase robot) {
        super.unsafeRelease(robot);
        scheduleRenderUpdateIfLoaded();
    }

    @Override
    public IInjectable getItemOutput() {
        return getPipe() != null && getPipe().getPipe().flow instanceof PipeFlowItems ? injectablePipe : null;
    }

    public boolean isWoodenItemPipe() {
        return getPipe() != null && getPipe().getPipe() != null
                && getPipe().getPipe().flow instanceof PipeFlowItems
                && getPipe().getPipe().behaviour instanceof PipeBehaviourWood;
    }

    public boolean isItemOutputBusy() {
        return getPipe() != null && getPipe().getPipe() != null
                && getPipe().getPipe().flow instanceof PipeFlowItems items
                && items.doesContainItems();
    }

    @Override
    public Direction getItemOutputSide() {
        return side();
    }

    @Override
    public Container getItemInput() {
        Direction inputSide = getItemInputPipeSide();
        Level level = level();
        if (getPipe() == null || inputSide == null || level == null) return null;
        BlockEntity neighbour = level.getBlockEntity(new net.minecraft.core.BlockPos(x(), y(), z()).relative(inputSide));
        return getNeighbourItemContainer(neighbour);
    }

    private static Container getNeighbourItemContainer(BlockEntity neighbour) {
        if (neighbour == null) {
            return null;
        }

        // A vanilla double chest is stored as two separate ChestBlockEntity instances. Returning the raw neighbour
        // container exposes only the contacted half, so robots treat a 54-slot chest as a 27-slot chest.
        Container doubleChest = BlockUtil.getCombinedDoubleChestContainer(neighbour);
        if (doubleChest != null) {
            return doubleChest;
        }

        return neighbour instanceof Container container ? container : null;
    }

    @Override
    public Direction getItemInputSide() {
        Direction inputSide = getItemInputPipeSide();
        return inputSide == null ? null : inputSide.getOpposite();
    }

    @Override
    public FluidStorage<FluidStack> getFluidOutput() {
        return getPipe() != null && getPipe().getPipe() != null && getPipe().getPipe().flow instanceof PipeFlowFluids
            ? StorageAdapters.fromNativeFluids(injectableFluidPipe) : null;
    }

    @Override
    public Direction getFluidOutputSide() {
        return side();
    }

    @Override
    public FluidStorage<FluidStack> getFluidInput() {
        Direction inputSide = getFluidInputPipeSide();
        if (getPipe() == null || inputSide == null || level() == null) return null;
        BlockPos neighbourPos = new BlockPos(x(), y(), z()).relative(inputSide);
        return PlatformStorage.fluids(level(), neighbourPos, inputSide.getOpposite());
    }

    @Override
    public Direction getFluidInputSide() {
        Direction inputSide = getFluidInputPipeSide();
        return inputSide == null ? null : inputSide.getOpposite();
    }

    private Direction getItemInputPipeSide() {
        if (getPipe() == null || getPipe().getPipe() == null) return null;
        if (!(getPipe().getPipe().flow instanceof PipeFlowItems)) return null;
        if (!(getPipe().getPipe().behaviour instanceof PipeBehaviourWood wood)) return null;
        Direction inputSide = wood.getCurrentDir();
        if (inputSide == null || inputSide == side()) return null;
        return inputSide;
    }

    private Direction getFluidInputPipeSide() {
        if (getPipe() == null || getPipe().getPipe() == null) return null;
        if (!(getPipe().getPipe().flow instanceof PipeFlowFluids)) return null;
        if (!(getPipe().getPipe().behaviour instanceof PipeBehaviourWood wood)) return null;
        Direction inputSide = wood.getCurrentDir();
        if (inputSide == null || inputSide == side()) return null;
        return inputSide;
    }

    @Override
    public boolean providesPower() {
        return getPipe() != null && getPipe().getPipe() != null && getPipe().getPipe().flow instanceof PipeFlowPower;
    }

    private ItemStack getRequest(int slot) {
        List<ItemStack> requests = getActiveItemRequests();
        return slot >= 0 && slot < requests.size() ? requests.get(slot).copy() : ItemStack.EMPTY;
    }

    @Override
    public Collection<ItemRequest> requests() {
        List<ItemStack> active = getActiveItemRequests();
        List<ItemRequest> result = new ArrayList<>(active.size());
        for (int slot = 0; slot < active.size(); slot++) {
            ItemStack request = active.get(slot);
            if (!request.isEmpty()) {
                result.add(RequestSupport.request(slot, request, active.size() - slot));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public ItemTransferResult offer(ResourceLocation requestId, ItemStack offered, OperationMode mode) {
        if (offered == null || offered.isEmpty()) {
            return ItemTransferResult.nothing(offered == null ? 0 : offered.getCount());
        }
        int slot = RequestSupport.slot(requestId).orElse(-1);
        if (slot < 0 || getRequest(slot).isEmpty()) {
            return ItemTransferResult.nothing(offered.getCount());
        }

        IInjectable output = getItemOutput();
        Direction outputSide = getItemOutputSide();
        if (output == null || outputSide == null || !output.canInjectItems(outputSide)) {
            return ItemTransferResult.nothing(offered.getCount());
        }
        ItemStack remainder = output.injectItem(offered.copy(), mode == OperationMode.EXECUTE, outputSide, null, 0.08D);
        return ItemTransferResult.ofInsertion(offered, offered.getCount() - remainder.getCount());
    }

    private List<ItemStack> getActiveItemRequests() {
        List<ItemStack> requests = new ArrayList<>();
        if (getPipe() == null || getPipe().getPipe() == null || !(getPipe().getPipe().flow instanceof PipeFlowItems)) {
            return requests;
        }

        for (StatementSlot slot : getActiveActions()) {
            if (!(slot.statement instanceof ActionStationRequestItems) || slot.parameters == null) {
                continue;
            }

            for (IStatementParameter parameter : slot.parameters) {
                if (parameter == null) {
                    continue;
                }
                ItemStack requested = parameter.getItemStack();
                if (!requested.isEmpty()) {
                    requests.add(requested.copy());
                }
            }
        }
        return requests;
    }

    @Override
    public RequestProvider getRequestProvider() {
        Level level = level();
        if (level != null) {
            BlockPos pos = new BlockPos(x(), y(), z());
            for (Direction dir : Direction.values()) {
                BlockEntity neighbour = level.getBlockEntity(pos.relative(dir));
                if (neighbour instanceof RequestProvider provider) {
                    return provider;
                }
            }
        }
        return this;
    }

    @Override
    public void onChunkUnload() {
        pipe = null;
        invalidSinceTick = Long.MIN_VALUE;
    }
}
