/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.permission.WorldOperationKind;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.builders.BuildersNbtUtil;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public abstract class SnapshotBuilder<T extends ITileForSnapshotBuilder> implements INBTSerializable<CompoundTag> {
    private static final int MAX_QUEUE_SIZE = 64;
    protected static final byte CHECK_RESULT_UNKNOWN = 0;
    protected static final byte CHECK_RESULT_CORRECT = 1;
    protected static final byte CHECK_RESULT_TO_BREAK = 2;
    protected static final byte CHECK_RESULT_TO_PLACE = 3;
    private static final byte REQUIRED_UNKNOWN = 0;
    private static final byte REQUIRED_TRUE = 1;
    private static final byte REQUIRED_FALSE = 2;
    private static final int CHECKS_PER_TICK = 24;
    private static final int FAST_CHECKS_PER_TICK = 128;
    private static final long MAX_POWER_PER_TICK = 512 * MjAmount.MICRO_MJ_PER_MJ;
    private static final int POWER_EFFICIENCY_MULTIPLIER = 2;

    protected final T tile;
	private BlockPositionSource blockPosSource;
    private final GameEventListener worldEventListener = new GameEventListener() {
    	@Override
    	public PositionSource getListenerSource() {
    		if(blockPosSource == null)
    			blockPosSource = new BlockPositionSource(tile.getBuilderPos());
    		return blockPosSource;
    	}
    	@Override
    	public int getListenerRadius() {
    		return 64;
    	}
    	@Override
        public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 pos) {
            if (event == GameEvent.BLOCK_PLACE || event == GameEvent.BLOCK_DESTROY) {
                if (tile.getBuilder() == SnapshotBuilder.this && getBuildingInfo() != null && getBuildingInfo().box.contains(pos)) {
                    if (check(BlockPos.containing(pos))) {
                        afterChecks();
                    }
                }
                return true;
    		}
    		return false;
    	}
    };
    private final Queue<BreakTask> breakTasks = new ArrayDeque<>();
    public final Queue<BreakTask> clientBreakTasks = new ArrayDeque<>();
   
    public final Queue<BreakTask> prevClientBreakTasks = new ArrayDeque<>();
    private final Queue<PlaceTask> placeTasks = new ArrayDeque<>();
    public final Queue<PlaceTask> clientPlaceTasks = new ArrayDeque<>();
   
    public final Queue<PlaceTask> prevClientPlaceTasks = new ArrayDeque<>();
   
    protected byte[] checkResults;
    private byte[] requiredCache;
    private int[] breakOrder;
    private int[] placeOrder;
    private int[] checkOrder;
    private int currentCheckIndex;
    private int unknownCheckResults = 0;
    public int leftToBreak = 0;
    public int leftToPlace = 0;
    private boolean renderWork = false;
    /** Energy removed for active tasks that could not yet be returned because the machine buffer was full. */
    private long pendingPowerRefund;

   
    protected SnapshotBuilder(T tile) {
        this.tile = tile;
    }

    /**
     * Runtime limits are exposed through accessors so specialised machines can be tuned without changing the
     * behaviour of every snapshot builder. The normal Builder keeps the original values, while machines such as the
     * Filler may override only the limits that are relevant to them.
     */
    protected int getChecksPerTick() {
        return CHECKS_PER_TICK;
    }

    protected int getFastChecksPerTick() {
        return FAST_CHECKS_PER_TICK;
    }

    protected long getMaxPowerPerTick() {
        return MAX_POWER_PER_TICK;
    }

    protected abstract Snapshot.BuildingInfo getBuildingInfo();

    /**
     * Iteration order for blocks that should be removed before building.
     * Kept behind an accessor so subclasses do not depend on field visibility.
     */
    protected int[] getBreakOrder() {
        return breakOrder;
    }

    /**
     * Iteration order for blocks that should be placed by normal builder logic or builder robots.
     * Kept behind an accessor so subclasses do not depend on field visibility.
     */
    protected int[] getPlaceOrder() {
        return placeOrder;
    }

    public void validate() {
    	Level level = tile.getWorldBC();
        if (!level.isClientSide) {
//        	BlockPos pos = tile.getBuilderPos();
//        	level.getChunk(pos).getEventDispatcher(SectionPos.blockToSectionCoord(pos.getY())).register(worldEventListener);
        }
    }

    public void invalidate() {
    	Level level = tile.getWorldBC();
        if (!level.isClientSide) {
//        	BlockPos pos = tile.getBuilderPos();
//        	level.getChunk(pos).getEventDispatcher(SectionPos.blockToSectionCoord(pos.getY())).unregister(worldEventListener);
        }
    }

    public boolean isInitialized() {
        return checkResults != null
            && requiredCache != null
            && breakOrder != null
            && placeOrder != null
            && checkOrder != null
            && checkOrder.length > 0;
    }

    public void resetWorkRendering() {
        renderWork = false;
    }

    /** Keeps excavation drops instead of letting the drop-capture helper delete them. */
    protected final void handleExcavationDrops(List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return;
        }

        IItemTransactor resources = null;
        if (tile instanceof ITileForBlueprintBuilder) {
            resources = ((ITileForBlueprintBuilder) tile).getInvResources();
        } else if (tile instanceof ITileForTemplateBuilder) {
            resources = ((ITileForTemplateBuilder) tile).getInvResources();
        }

        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack remainder = drop.copy();
            if (resources != null) {
                remainder = resources.insert(remainder, false, false);
            }
            if (!remainder.isEmpty()) {
                InventoryUtil.addToBestAcceptor(tile.getWorldBC(), tile.getBuilderPos(), null, remainder);
            }
        }
    }

    /**
     * Fingerprint of the non-interpolated part of {@link #writeToByteBuf(FriendlyByteBuf)}. A change here must be sent
     * immediately: it means that a task appeared/disappeared, its target changed, or rendering was stopped.
     */
    public int getRenderStructureFingerprint() {
        int result = Boolean.hashCode(renderWork);
        if (!renderWork) {
            return result;
        }
        result = 31 * result + breakTasks.size();
        for (BreakTask task : breakTasks) {
            result = 31 * result + task.pos.hashCode();
        }
        result = 31 * result + placeTasks.size();
        for (PlaceTask task : placeTasks) {
            result = 31 * result + task.pos.hashCode();
            result = 31 * result + task.items.size();
            for (ItemStack stack : task.items) {
                result = 31 * result + stack.getItem().hashCode();
                result = 31 * result + stack.getCount();
                result = 31 * result + stack.getComponents().hashCode();
            }
        }
        return result;
    }

    /** Fingerprint of all render data, including task progress that may be rate-limited for network transmission. */
    public int getRenderDataFingerprint() {
        int result = getRenderStructureFingerprint();
        result = 31 * result + leftToBreak;
        result = 31 * result + leftToPlace;
        if (!renderWork) {
            return result;
        }
        for (BreakTask task : breakTasks) {
            result = 31 * result + Long.hashCode(task.power);
        }
        for (PlaceTask task : placeTasks) {
            result = 31 * result + Long.hashCode(task.power);
        }
        return result;
    }

    protected abstract boolean isAir(BlockPos blockPos);

    protected abstract boolean canPlace(BlockPos blockPos);

    protected abstract boolean isReadyToPlace(BlockPos blockPos);

    protected abstract boolean hasEnoughToPlaceItems(BlockPos blockPos);

    protected abstract List<ItemStack> getToPlaceItems(BlockPos blockPos);

    /**
     * @return true if task done successfully, false otherwise
     */
    protected abstract boolean doPlaceTask(PlaceTask placeTask);

    /**
     * Executed if break task failed
     */
    private void cancelBreakTask(BreakTask breakTask) {
        queuePowerRefund(breakTask.reservedPower);
    }

    /**
     * Executed if {@link #doPlaceTask} failed
     */
    protected void cancelPlaceTask(PlaceTask placeTask) {
        queuePowerRefund(placeTask.reservedPower);
    }

    private PlacementRollback capturePlacementRollback(BlockPos pos) {
        if (!(tile.getWorldBC() instanceof ServerLevel level)) {
            return null;
        }
        return new PlacementRollback(
            BlockSnapshot.create(level.dimension(), level, pos),
            level.getBlockState(pos)
        );
    }

    private boolean rollbackPlacement(PlacementRollback rollback, BlockPos pos) {
        if (rollback == null) {
            // SnapshotBuilder work is server-side; retain the legacy refund behaviour for non-server test worlds.
            return true;
        }
        rollback.snapshot.restore();
        return tile.getWorldBC().getBlockState(pos).equals(rollback.previousState);
    }

    private static final class PlacementRollback {
        private final BlockSnapshot snapshot;
        private final BlockState previousState;

        private PlacementRollback(BlockSnapshot snapshot, BlockState previousState) {
            this.snapshot = snapshot;
            this.previousState = previousState;
        }
    }

    private void queuePowerRefund(long power) {
        if (power <= 0) {
            return;
        }
        pendingPowerRefund += Math.min(power, Long.MAX_VALUE - pendingPowerRefund);
        markTileDirty();
        flushPendingPowerRefund();
    }

    private void flushPendingPowerRefund() {
        if (pendingPowerRefund <= 0) {
            return;
        }
        long free = Math.max(0, tile.getBattery().getCapacity() - tile.getBattery().getStored());
        long refunded = Math.min(free, pendingPowerRefund);
        if (refunded > 0) {
            tile.getBattery().addPower(refunded, FluidAction.EXECUTE);
            pendingPowerRefund -= refunded;
            markTileDirty();
        }
    }

    protected void markTileDirty() {
        if (tile instanceof BlockEntity blockEntity) {
            blockEntity.setChanged();
        }
    }

    /**
     * @return true if block in wold is correct (is not to break) according to snapshot, false otherwise
     */
    protected abstract boolean isBlockCorrect(BlockPos blockPos);

    /**
     * @return Pos where flying item should be rendered
     */
    public Vec3 getPlaceTaskItemPos(PlaceTask placeTask) {
        Vec3 height = Vec3.atLowerCornerOf(placeTask.pos.subtract(tile.getBuilderPos()));
        double progress = placeTask.power * 1D / placeTask.getTarget();
        return Vec3.atLowerCornerOf(tile.getBuilderPos())
            .add(height.scale(progress))
            .add(new Vec3(0, Math.sin(progress * Math.PI) * (Math.abs(height.y) + 1), 0))
            .add(new Vec3(0.5, 1, 0.5));
    }

    public void updateSnapshot() {
        Snapshot.BuildingInfo buildingInfo = getBuildingInfo();
        if (buildingInfo == null) {
            // Client block entities may receive the marker box before the filler pattern/building info.
            // Treat that transient state as "not initialized" instead of dereferencing a missing snapshot.
            cancel();
            return;
        }

        tile.getWorldBC().getProfiler().push("init");
        int size = buildingInfo.box.size().getX() *
            buildingInfo.box.size().getY() *
            buildingInfo.box.size().getZ();
        checkResults = new byte[size];
        Arrays.fill(checkResults, CHECK_RESULT_UNKNOWN);
        unknownCheckResults = checkResults.length;
        requiredCache = new byte[size];
        Arrays.fill(requiredCache, REQUIRED_UNKNOWN);
        breakOrder = buildingInfo.box.getBlocksInArea().stream()
            .sorted(BlockUtil.uniqueBlockPosComparator(Comparator.comparingDouble(blockPos ->
                Math.pow(blockPos.getX() - buildingInfo.box.center().getX(), 2) +
                    Math.pow(blockPos.getZ() - buildingInfo.box.center().getZ(), 2) +
                    100_000 - Math.abs(blockPos.getY() - tile.getBuilderPos().getY()) * 100_000
            )))
            .mapToInt(this::posToIndex)
            .toArray();
        placeOrder = buildingInfo.box.getBlocksInArea().stream()
            .sorted(BlockUtil.uniqueBlockPosComparator(Comparator.comparingDouble(blockPos ->
                100_000 - (Math.pow(blockPos.getX() - tile.getBuilderPos().getX(), 2) +
                    Math.pow(blockPos.getZ() - tile.getBuilderPos().getZ(), 2)) +
                    Math.abs(blockPos.getY() - tile.getBuilderPos().getY()) * 100_000
            )))
            .mapToInt(this::posToIndex)
            .toArray();
        checkOrder = buildingInfo.box.getBlocksInArea().stream()
            .sorted(BlockUtil.uniqueBlockPosComparator(Comparator.comparingDouble(blockPos ->
                Math.pow(blockPos.getX() - buildingInfo.box.center().getX(), 2) +
                    Math.pow(blockPos.getY() - buildingInfo.box.center().getY(), 2) +
                    Math.pow(blockPos.getZ() - buildingInfo.box.center().getZ(), 2)
            )))
            .mapToInt(this::posToIndex)
            .toArray();
        tile.getWorldBC().getProfiler().pop();
    }

    public void resourcesChanged() {
        if (requiredCache != null) {
            Arrays.fill(requiredCache, REQUIRED_UNKNOWN);
        }
    }

    public void forceRecheckCurrentTask() {
        if (getBuildingInfo() == null) {
            cancel();
            return;
        }
        if (!isInitialized()) {
            updateSnapshot();
        } else {
            Arrays.fill(checkResults, CHECK_RESULT_UNKNOWN);
            Arrays.fill(requiredCache, REQUIRED_UNKNOWN);
            currentCheckIndex = 0;
            unknownCheckResults = checkResults.length;
        }
        clearActiveTasks();
        resourcesChanged();
    }

    public void cancel() {
        breakTasks.forEach(this::cancelBreakTask);
        placeTasks.forEach(this::cancelPlaceTask);
        breakTasks.clear();
        clientBreakTasks.clear();
        prevClientBreakTasks.clear();
        placeTasks.clear();
        clientPlaceTasks.clear();
        prevClientPlaceTasks.clear();
        checkResults = null;
        requiredCache = null;
        breakOrder = null;
        placeOrder = null;
        checkOrder = null;
        currentCheckIndex = 0;
        unknownCheckResults = 0;
        leftToBreak = 0;
        leftToPlace = 0;
        renderWork = false;
    }

    private void clearActiveTasks() {
        breakTasks.forEach(this::cancelBreakTask);
        placeTasks.forEach(this::cancelPlaceTask);
        breakTasks.clear();
        clientBreakTasks.clear();
        prevClientBreakTasks.clear();
        placeTasks.clear();
        clientPlaceTasks.clear();
        prevClientPlaceTasks.clear();
        leftToBreak = 0;
        leftToPlace = 0;
        renderWork = false;
    }

    /**
     * @return true is building is finished, false otherwise
     */
    @SuppressWarnings("resource")
	public boolean tick() {
    	Level level = tile.getWorldBC();
        if (level.isClientSide) {
            prevClientBreakTasks.clear();
            prevClientBreakTasks.addAll(clientBreakTasks);
            clientBreakTasks.clear();
            clientBreakTasks.addAll(breakTasks);
            prevClientPlaceTasks.clear();
            prevClientPlaceTasks.addAll(clientPlaceTasks);
            clientPlaceTasks.clear();
            clientPlaceTasks.addAll(placeTasks);
            return false;
        }

        flushPendingPowerRefund();

        if (!isInitialized()) {
            if (getBuildingInfo() == null) {
                return false;
            }
            updateSnapshot();
        }
        if (!isInitialized()) {
            return false;
        }

        boolean checkResultsChanged = false;

        tile.getWorldBC().getProfiler().push("scan");
        int checksThisTick = unknownCheckResults > 0 ? getFastChecksPerTick() : getChecksPerTick();
        for (int i = 0; i < checksThisTick; i++) {
            if (check(indexToPos(currentCheckIndex))) {
                checkResultsChanged = true;
            }
            currentCheckIndex = (currentCheckIndex + 1) % checkOrder.length;
        }
        
        int checkIndex0 = (currentCheckIndex + 1) % checkOrder.length;
        boolean isDone = checkResults[checkIndex0] != CHECK_RESULT_UNKNOWN; //FIXED add uncheckResult check
        
        tile.getWorldBC().getProfiler().pop();

        tile.getWorldBC().getProfiler().push("remove tasks");
        tile.getWorldBC().getProfiler().push("break");
        for (Iterator<BreakTask> iterator = breakTasks.iterator(); iterator.hasNext(); ) {
            BreakTask breakTask = iterator.next();
            if (checkResults[posToIndex(breakTask.pos)] == CHECK_RESULT_CORRECT) {
                iterator.remove();
                cancelBreakTask(breakTask);
            }
        }
        tile.getWorldBC().getProfiler().popPush("place");
        for (Iterator<PlaceTask> iterator = placeTasks.iterator(); iterator.hasNext(); ) {
            PlaceTask placeTask = iterator.next();
            if (checkResults[posToIndex(placeTask.pos)] == CHECK_RESULT_CORRECT) {
                iterator.remove();
                cancelPlaceTask(placeTask);
            }
        }
        tile.getWorldBC().getProfiler().pop();
        tile.getWorldBC().getProfiler().pop();

        tile.getWorldBC().getProfiler().push("add tasks");
        tile.getWorldBC().getProfiler().push("break");
        if (tile.canExcavate()) {
            Set<Integer> breakTasksIndexes = breakTasks.stream()
                .map(breakTask -> posToIndex(breakTask.pos))
                .collect(Collectors.toSet());
            int[] blocks = Arrays.stream(breakOrder)
                .filter(i -> checkResults[i] == CHECK_RESULT_TO_BREAK && !breakTasksIndexes.contains(i))
                .toArray();
            leftToBreak = blocks.length;
            if (blocks.length != 0) {
                isDone = false;
            }
            Arrays.stream(blocks)
                .mapToObj(this::indexToPos)
                .filter(blockPos -> BlockUtil.getFluidWithFlowing(tile.getWorldBC(), blockPos) == Fluids.EMPTY)
                .filter(blockPos -> AutomationPermissionUtil.mayBlock(
                    tile.getWorldBC(), tile.getBuilderPos(), blockPos, tile.getOwner(),
                    AutomationPermissionUtil.SOURCE_BUILDER, WorldOperationKind.BLOCK_BREAK, OperationMode.SIMULATE
                ))
                .map(blockPos ->
                    new BreakTask(
                        blockPos,
                        0
                    )
                )
                .limit(MAX_QUEUE_SIZE - breakTasks.size())
                .forEach(breakTasks::add);
        } else {
            leftToBreak = 0;
        }
        tile.getWorldBC().getProfiler().pop();
        tile.getWorldBC().getProfiler().push("place");
        {
            Set<Integer> placeTasksIndexes = placeTasks.stream()
                .map(placeTask -> posToIndex(placeTask.pos))
                .collect(Collectors.toSet());
            int[] blocks = Arrays.stream(placeOrder)
                .filter(i -> checkResults[i] == CHECK_RESULT_TO_PLACE && !placeTasksIndexes.contains(i))
                .toArray();
            leftToPlace = blocks.length;
            if (!tile.canExcavate() || (leftToBreak == 0 && breakTasks.isEmpty())) {
                if (blocks.length != 0) {
                    isDone = false;
                }
                Arrays.stream(blocks)
                    .filter(i -> {
                        if (requiredCache[i] != REQUIRED_UNKNOWN) {
                            return requiredCache[i] == REQUIRED_TRUE;
                        }
                        boolean has = hasEnoughToPlaceItems(indexToPos(i));
                        requiredCache[i] = has ? REQUIRED_TRUE : REQUIRED_FALSE;
                        return has;
                    })
                    .mapToObj(this::indexToPos)
                    .filter(this::isReadyToPlace)
                    .limit(MAX_QUEUE_SIZE - placeTasks.size())
                    .filter(this::canPlace)
                    .map(blockPos ->
                        new PlaceTask(
                            blockPos,
                            getToPlaceItems(blockPos),
                            0
                        )
                    )
                    .filter(placeTask -> placeTask.items != null)
                    .forEach(placeTasks::add);
            }
        }
        tile.getWorldBC().getProfiler().pop();
        tile.getWorldBC().getProfiler().pop();

        tile.getWorldBC().getProfiler().push("do tasks");
        long maxPowerPerTick = getMaxPowerPerTick();
        long max = Math.min(
            (long) (
                maxPowerPerTick *
                    (double) (tile.getBattery().getStored() + maxPowerPerTick / 10) /
                    Math.max(1.0, tile.getBattery().getCapacity() / 2.0)
            ),
            maxPowerPerTick
        );
        tile.getWorldBC().getProfiler().push("break");
        if (!breakTasks.isEmpty()) {
            for (Iterator<BreakTask> iterator = breakTasks.iterator(); iterator.hasNext(); ) {
                BreakTask breakTask = iterator.next();
                if (breakTask.isImpossible()) {
                    // A task may become unbreakable after energy was reserved (for example after a protection/config
                    // change). Do not leave its reservation trapped forever.
                    cancelBreakTask(breakTask);
                    iterator.remove();
                    continue;
                }
                long target = breakTask.getTarget();
                long progress = extractProgressPower(
                    target,
                    breakTask.power,
                    max / breakTasks.size(),
                    extracted -> breakTask.reservedPower = saturatingAdd(breakTask.reservedPower, extracted)
                );
                breakTask.power += progress;
                if (progress > 0) {
                    renderWork = true;
                }
                if (breakTask.power >= target) {
                    if (!AutomationPermissionUtil.mayBlock(
                        tile.getWorldBC(), tile.getBuilderPos(), breakTask.pos, tile.getOwner(),
                        AutomationPermissionUtil.SOURCE_BUILDER, WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
                    )) {
                        cancelBreakTask(breakTask);
                        if (check(breakTask.pos)) {
                            checkResultsChanged = true;
                        }
                        iterator.remove();
                        continue;
                    }
                    tile.getWorldBC().getProfiler().push("work");
                    tile.getWorldBC().destroyBlockProgress(
                        breakTask.pos.hashCode(),
                        breakTask.pos,
                        -1
                    );
                    Optional<List<ItemStack>> stacks = BlockUtil.breakBlockAndGetDrops(
                        (ServerLevel) tile.getWorldBC(),
                        breakTask.pos,
                        new ItemStack(Items.DIAMOND_PICKAXE),
                        tile.getOwner()
                    );
                    tile.getWorldBC().getProfiler().pop();
                    if (!stacks.isPresent()) {
                        cancelBreakTask(breakTask);
                    } else {
                        handleExcavationDrops(stacks.get());
                    }
                    if (check(breakTask.pos)) {
                        checkResultsChanged = true;
                    }
                    iterator.remove();
                } else {
                    tile.getWorldBC().getProfiler().push("work");
                    tile.getWorldBC().destroyBlockProgress(
                        breakTask.pos.hashCode(),
                        breakTask.pos,
                        (int) ((breakTask.power * 9) / target)
                    );
                    tile.getWorldBC().getProfiler().pop();
                }
            }
        }
        tile.getWorldBC().getProfiler().popPush("place");
        if (!placeTasks.isEmpty()) {
            for (Iterator<PlaceTask> iterator = placeTasks.iterator(); iterator.hasNext(); ) {
                PlaceTask placeTask = iterator.next();
                long target = placeTask.getTarget();
                long progress = extractProgressPower(
                    target,
                    placeTask.power,
                    max / placeTasks.size(),
                    extracted -> placeTask.reservedPower = saturatingAdd(placeTask.reservedPower, extracted)
                );
                placeTask.power += progress;
                if (progress > 0) {
                    renderWork = true;
                }
                if (placeTask.power >= target) {
                    tile.getWorldBC().getProfiler().push("work");
                    PlacementRollback rollback = capturePlacementRollback(placeTask.pos);
                    doPlaceTask(placeTask);
                    // Commit resources only when the requested schematic state exists. If a handler partially changed
                    // the target and then failed, restore the exact pre-placement block snapshot before refunding.
                    // If rollback itself cannot be proven, keep the reservation consumed rather than creating items/MJ.
                    if (!isBlockCorrect(placeTask.pos)) {
                        if (rollbackPlacement(rollback, placeTask.pos)) {
                            cancelPlaceTask(placeTask);
                        } else {
                            BCLog.logger.warn(
                                "Builder placement at {} failed and could not be rolled back; keeping reservations consumed",
                                placeTask.pos
                            );
                        }
                    }
                    tile.getWorldBC().getProfiler().pop();
                    if (check(placeTask.pos)) {
                        checkResultsChanged = true;
                    }
                    iterator.remove();
                }
            }
        }
        tile.getWorldBC().getProfiler().pop();
        tile.getWorldBC().getProfiler().pop();

        if (checkResultsChanged) {
            afterChecks();
        }
//        if(isDone)
        //	BCLog.d(getBuildingInfo().box+" : done");
  //      return isDone;
        boolean isDone0 = true;
        for(int i0 = 0; (i0 < checkResults.length)&&isDone0 ; i0++) {
        	isDone0 = (checkResults[i0] == CHECK_RESULT_CORRECT);
        }
        return isDone0;
    }


    private long extractProgressPower(long target, long currentProgress, long maxPower,
                                      java.util.function.LongConsumer reservePower) {
        long remainingProgress = target - currentProgress;
        if (remainingProgress <= 0 || maxPower <= 0) {
            return 0;
        }
        long wantedPower = Math.min(ceilDiv(remainingProgress, POWER_EFFICIENCY_MULTIPLIER), maxPower);
        long extractedPower = tile.getBattery().extractPower(0, wantedPower);
        if (extractedPower > 0) {
            // Progress is scaled by POWER_EFFICIENCY_MULTIPLIER, while refunds must use the physical amount
            // removed from the battery so energy remains conserved.
            reservePower.accept(extractedPower);
        }
        return Math.min(remainingProgress, extractedPower * POWER_EFFICIENCY_MULTIPLIER);
    }

    static long legacyReservedPowerForProgress(long progress) {
        return progress <= 0 ? 0 : ceilDiv(progress, POWER_EFFICIENCY_MULTIPLIER);
    }

    static long saturatingAdd(long left, long right) {
        if (right <= 0) return Math.max(0, left);
        if (left >= Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return Math.max(0, left) + right;
    }

    private static long ceilDiv(long value, long divisor) {
        return value <= 0 ? 0 : 1 + (value - 1) / divisor;
    }

    protected int posToIndex(BlockPos blockPos) {
        return getBuildingInfo().getSnapshot().posToIndex(getBuildingInfo().fromWorld(blockPos));
    }

   
    protected BlockPos indexToPos(int i) {
        return getBuildingInfo().toWorld(getBuildingInfo().getSnapshot().indexToPos(i));
    }

    /**
     * @return true if changed, false otherwise
     */
    protected boolean check(BlockPos blockPos) {
        int i = posToIndex(blockPos);
        byte prev = checkResults[i];
        if (isAir(blockPos)) {
            if (tile.getWorldBC().isEmptyBlock(blockPos)) {
                checkResults[i] = CHECK_RESULT_CORRECT;
            } else {
                checkResults[i] = CHECK_RESULT_TO_BREAK;
            }
        } else {
            if (isBlockCorrect(blockPos)) {
                checkResults[i] = CHECK_RESULT_CORRECT;
            } else if (canPlace(blockPos)) {
                // Filler/template placement may replace grass, snow layers, plants and other replaceable states
                // directly. Do not turn those into excavation tasks first.
                checkResults[i] = CHECK_RESULT_TO_PLACE;
            } else if (!tile.getWorldBC().isEmptyBlock(blockPos)) {
                checkResults[i] = CHECK_RESULT_TO_BREAK;
            } else {
                checkResults[i] = CHECK_RESULT_TO_BREAK;
            }
        }
        if (prev == CHECK_RESULT_UNKNOWN && checkResults[i] != CHECK_RESULT_UNKNOWN && unknownCheckResults > 0) {
            unknownCheckResults--;
        }
        return prev != checkResults[i];
    }

    private int countUnknownCheckResults() {
        int unknown = 0;
        for (byte checkResult : checkResults) {
            if (checkResult == CHECK_RESULT_UNKNOWN) {
                unknown++;
            }
        }
        return unknown;
    }

    protected void afterChecks() {
    }

    public void stopRenderingForNoPower() {
        renderWork = false;
        if (!tile.getWorldBC().isClientSide) {
            for (BreakTask breakTask : breakTasks) {
                tile.getWorldBC().destroyBlockProgress(breakTask.pos.hashCode(), breakTask.pos, -1);
            }
        }
        clientBreakTasks.clear();
        prevClientBreakTasks.clear();
        clientPlaceTasks.clear();
        prevClientPlaceTasks.clear();
    }
    
    public GameEventListener getListener() {
    	return this.worldEventListener;
    }

    public void writeToByteBuf(FriendlyByteBuf buffer) {
        if (!renderWork) {
            buffer.writeInt(0);
            buffer.writeInt(0);
            buffer.writeInt(leftToBreak);
            buffer.writeInt(leftToPlace);
            return;
        }
        buffer.writeInt(breakTasks.size());
        breakTasks.forEach(breakTask -> breakTask.writePayload(buffer));
        buffer.writeInt(placeTasks.size());
        placeTasks.forEach(placeTask -> placeTask.writePayload(buffer));
        buffer.writeInt(leftToBreak);
        buffer.writeInt(leftToPlace);
    }

    public void readFromByteBuf(FriendlyByteBuf buffer) {
        breakTasks.clear();
        IntStream.range(0, buffer.readInt()).mapToObj(i -> new BreakTask(buffer)).forEach(breakTasks::add);
        placeTasks.clear();
        IntStream.range(0, buffer.readInt()).mapToObj(i -> new PlaceTask(buffer)).forEach(placeTasks::add);
        leftToBreak = buffer.readInt();
        leftToPlace = buffer.readInt();
    }

    public CompoundTag serializeNBT() {
        return serializeNBT(ItemStackUtil.requireActiveRegistryProvider());
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        if (pendingPowerRefund > 0) {
            nbt.putLong("pendingPowerRefund", pendingPowerRefund);
        }
        if (!isInitialized()) {
            return nbt;
        }
        nbt.putByteArray("checkResults", checkResults);
        nbt.put("breakTasks", NBTUtilBC.writeObjectList(breakTasks.stream().map(BreakTask::writeToNBT)));
        nbt.put("placeTasks", NBTUtilBC.writeObjectList(placeTasks.stream().map(task -> task.writeToNBT(registries))));
        nbt.putInt("currentCheckIndex", currentCheckIndex);
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) {
        deserializeNBT(ItemStackUtil.requireActiveRegistryProvider(), nbt);
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag nbt) {
        // Per-block completion state is intentionally rechecked against the live world, but resources already
        // reserved by saved tasks must be returned rather than silently discarded.
        pendingPowerRefund = Math.max(0, nbt.getLong("pendingPowerRefund"));
        NBTUtilBC.readCompoundList(nbt.get("breakTasks"))
            .map(BreakTask::new)
            .forEach(this::cancelBreakTask);
        NBTUtilBC.readCompoundList(nbt.get("placeTasks"))
            .map(tag -> new PlaceTask(tag, registries))
            .forEach(this::cancelPlaceTask);
        forceRecheckCurrentTask();
        flushPendingPowerRefund();
    }

    public class BreakTask {
        public final BlockPos pos;
        /** Visual/work progress. This is not necessarily equal to MJ withdrawn from the battery. */
        public long power;
        /** Exact battery energy reserved for this task and refundable until the world mutation commits. */
        public long reservedPower;

       
        public BreakTask(BlockPos pos, long power) {
            this.pos = pos;
            this.power = power;
            this.reservedPower = legacyReservedPowerForProgress(power);
        }

       
        public BreakTask(FriendlyByteBuf buffer) {
            pos = MessageUtil.readBlockPos(buffer);
            power = buffer.readLong();
            reservedPower = 0; // render payloads never own server-side reservations
        }

       
        public BreakTask(CompoundTag nbt) {
            pos = BuildersNbtUtil.readBlockPos(nbt, "pos");
            power = nbt.getLong("power");
            reservedPower = nbt.contains("reservedPower")
                ? Math.max(0, nbt.getLong("reservedPower"))
                : legacyReservedPowerForProgress(power);
        }

       
        public boolean isImpossible() {
            return BlockUtil.isUnbreakableBlock(tile.getWorldBC(), pos, tile.getOwner());
        }

        public long getTarget() {
            return BlockUtil.computeBlockBreakPower(tile.getWorldBC(), pos);
        }

        public void writePayload(FriendlyByteBuf buffer) {
            MessageUtil.writeBlockPos(buffer, pos);
            buffer.writeLong(power);
        }

        public CompoundTag writeToNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.put("pos", NbtUtils.writeBlockPos(pos));
            nbt.putLong("power", power);
            nbt.putLong("reservedPower", reservedPower);
            return nbt;
        }
    }

    public class PlaceTask {
        public final BlockPos pos;
        public final List<ItemStack> items;
        /** Visual/work progress. */
        public long power;
        /** Exact battery energy reserved for this task and refundable until placement commits. */
        public long reservedPower;

       
        public PlaceTask(BlockPos pos, List<ItemStack> items, long power) {
            this.pos = pos;
            this.items = Optional.ofNullable(items).map(ImmutableList::copyOf).orElse(null);
            this.power = power;
            this.reservedPower = legacyReservedPowerForProgress(power);
        }

       
        public PlaceTask(FriendlyByteBuf buffer) {
            pos = MessageUtil.readBlockPos(buffer);
            items = IntStream.range(0, buffer.readInt())
                .mapToObj(j -> ItemStackUtil.readOptional(buffer))
                .collect(Collectors.toList());
            power = buffer.readLong();
            reservedPower = 0; // render payloads never own server-side reservations
        }

       
        public PlaceTask(CompoundTag nbt, HolderLookup.Provider registries) {
            pos = BuildersNbtUtil.readBlockPos(nbt, "pos");
            items = ImmutableList.copyOf(
                NBTUtilBC.readCompoundList(nbt.get("items"))
                    .map(tag -> ItemStackUtil.parseOptional(registries, tag)).toList()
            );
            power = nbt.getLong("power");
            reservedPower = nbt.contains("reservedPower")
                ? Math.max(0, nbt.getLong("reservedPower"))
                : legacyReservedPowerForProgress(power);
        }

        public long getTarget() {
            return (long) (Math.sqrt(pos.distSqr(tile.getBuilderPos())) * 10 * MjAmount.MICRO_MJ_PER_MJ);
        }

        public void writePayload(FriendlyByteBuf buffer) {
            MessageUtil.writeBlockPos(buffer, pos);
            buffer.writeInt(items.size());
            items.forEach(stack -> ItemStackUtil.writeOptional(buffer, stack));
            buffer.writeLong(power);
        }

        public CompoundTag writeToNBT(HolderLookup.Provider registries) {
            CompoundTag nbt = new CompoundTag();
            nbt.put("pos", NbtUtils.writeBlockPos(pos));
            nbt.put("items", NBTUtilBC.writeObjectList(items.stream().map(stack -> ItemStackUtil.saveOptional(stack, registries))));
            nbt.putLong("power", power);
            nbt.putLong("reservedPower", reservedPower);
            return nbt;
        }
    }
}
