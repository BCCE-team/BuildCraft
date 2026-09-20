/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.permission.WorldOperationKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import buildcraft.lib.misc.*;
import org.apache.commons.lang3.tuple.Pair;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.api2.Api2SchematicEntity;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.builders.internal.schematic.legacy.SchematicEntityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

public class BlueprintBuilder extends SnapshotBuilder<ITileForBlueprintBuilder> {
    private static final double MAX_ENTITY_DISTANCE = 0.1D;
    private static final String FLUID_STACK_KEY = "BuilderFluidStack";
    private static final int DEFERRED_INVENTORY_SCAN_BUDGET = 64;
    private static final int DEFERRED_INVENTORY_INSERT_BUDGET = 8;

    private List<ItemStack>[] remainingDisplayRequiredBlocks;
    private List<ItemStack> remainingDisplayRequiredBlocksConcat = Collections.emptyList();
    private List<ItemStack>[] remainingDeferredRequiredBlocks;
    private List<ItemStack> remainingDeferredRequiredBlocksConcat = Collections.emptyList();
    private int deferredInventoryCursor;
    public List<ItemStack> remainingDisplayRequired = new ArrayList<>();
    private final Map<Pair<List<ItemStack>, List<FluidStack>>, Optional<List<ItemStack>>> extractRequiredCache =
        new HashMap<>();
    private final Set<BlockPos> robotReservedBlocks = new HashSet<>();
    /** Fluids reserved by cancelled tasks that are waiting for room in the builder tanks. */
    private final List<FluidStack> pendingFluidRefunds = new ArrayList<>();

    public BlueprintBuilder(ITileForBlueprintBuilder tile) {
        super(tile);
    }

    private ISchematicBlock getSchematicBlock(BlockPos blockPos) {
        return getBuildingInfo().box.contains(blockPos)
            ?
            getBuildingInfo().rotatedPalette.get(
                getBuildingInfo().getSnapshot().data[getBuildingInfo().getSnapshot().posToIndex(
                    getBuildingInfo().fromWorld(blockPos)
                )]
            )
            : null;
    }

    private ISchematicBlock getSchematicBlock(int index) {
        return getBuildingInfo().rotatedPalette.get(getBuildingInfo().getSnapshot().data[index]);
    }

    @Override
    protected boolean isAir(BlockPos blockPos) {
        // noinspection ConstantConditions
        return getSchematicBlock(blockPos) == null || getSchematicBlock(blockPos).isAir();
    }

    @Override
    protected Blueprint.BuildingInfo getBuildingInfo() {
        return tile.getBlueprintBuildingInfo();
    }

    @Override
    public void updateSnapshot() {
        super.updateSnapshot();
        if (!isInitialized()) {
            return;
        }
        extractRequiredCache.clear();
        robotReservedBlocks.clear();
        deferredInventoryCursor = 0;
        int dataSize = getBuildingInfo().getSnapshot().getDataSize();
        // noinspection unchecked
        remainingDisplayRequiredBlocks = (List<ItemStack>[]) new List<?>[dataSize];
        Arrays.fill(remainingDisplayRequiredBlocks, Collections.emptyList());
        // noinspection unchecked
        remainingDeferredRequiredBlocks = (List<ItemStack>[]) new List<?>[dataSize];
        for (int index = 0; index < dataSize; index++) {
            List<ItemStack> required = getBuildingInfo().toPlaceDeferredItems[index];
            if (required == null || required.isEmpty()) {
                remainingDeferredRequiredBlocks[index] = Collections.emptyList();
                continue;
            }
            BlockPos blockPos = indexToPos(index);
            ISchematicBlock schematicBlock = getSchematicBlock(index);
            remainingDeferredRequiredBlocks[index] = schematicBlock.isBuilt(tile.getWorldBC(), blockPos)
                ? copyStacks(schematicBlock.computeMissingDeferredRequiredItems(tile.getWorldBC(), blockPos))
                : copyStacks(required);
        }
        updateDeferredRequiredConcat();
    }

    @Override
    public void resourcesChanged() {
        super.resourcesChanged();
        extractRequiredCache.clear();
        robotReservedBlocks.clear();
    }

    @Override
    public void cancel() {
        super.cancel();
        remainingDisplayRequiredBlocks = null;
        remainingDisplayRequiredBlocksConcat = Collections.emptyList();
        remainingDeferredRequiredBlocks = null;
        remainingDeferredRequiredBlocksConcat = Collections.emptyList();
        deferredInventoryCursor = 0;
        remainingDisplayRequired.clear();
        extractRequiredCache.clear();
        robotReservedBlocks.clear();
    }

    private Stream<ItemStack> getDisplayRequired(List<ItemStack> requiredItems, List<FluidStack> requiredFluids) {
        return Stream.concat(
            requiredItems == null ? Stream.empty() : requiredItems.stream(),
            requiredFluids == null ? Stream.empty() : requiredFluids.stream()
                .map(FluidUtilBC::getFragileFluid)
        );
    }

    private Optional<List<ItemStack>> tryExtractRequired(List<ItemStack> requiredItems,
                                                         List<FluidStack> requiredFluids,
                                                         boolean simulate) {
        List<ItemStack> items = requiredItems == null ? Collections.emptyList() : requiredItems;
        List<FluidStack> fluids = requiredFluids == null ? Collections.emptyList() : requiredFluids;
        List<ItemStack> mergedItems = StackUtil.mergeSameItems(items);
        List<FluidStack> mergedFluids = FluidUtilBC.mergeSameFluids(fluids);

        Supplier<Optional<List<ItemStack>>> preflight = () -> {
            boolean enoughItems = mergedItems.stream().allMatch(required -> {
                ItemStack extracted = tile.getInvResources().extract(
                    candidate -> StackUtil.canMerge(required, candidate),
                    required.getCount(), required.getCount(), true
                );
                return !extracted.isEmpty() && extracted.getCount() == required.getCount();
            });
            boolean enoughFluids = mergedFluids.stream().allMatch(required ->
                FluidUtilBC.areFluidStackEqual(required, tile.getTankManager().drain(required, FluidAction.SIMULATE))
            );
            return enoughItems && enoughFluids ? Optional.of(Collections.emptyList()) : Optional.empty();
        };

        if (simulate) {
            return extractRequiredCache.computeIfAbsent(Pair.of(items, fluids), pair -> preflight.get());
        }
        if (preflight.get().isEmpty()) {
            return Optional.empty();
        }

        List<ItemStack> reserved = new ArrayList<>();
        List<FluidStack> drained = new ArrayList<>();
        for (ItemStack required : mergedItems) {
            ItemStack extracted = tile.getInvResources().extract(
                candidate -> StackUtil.canMerge(required, candidate),
                required.getCount(), required.getCount(), false
            );
            if (extracted.isEmpty() || extracted.getCount() != required.getCount()) {
                if (!extracted.isEmpty()) reserved.add(extracted);
                rollbackReservation(reserved, drained);
                return Optional.empty();
            }
            reserved.add(extracted);
        }
        for (FluidStack required : mergedFluids) {
            FluidStack extracted = tile.getTankManager().drain(required, FluidAction.EXECUTE);
            if (!extracted.isEmpty()) drained.add(extracted.copy());
            if (!FluidUtilBC.areFluidStackEqual(required, extracted)) {
                rollbackReservation(reserved, drained);
                return Optional.empty();
            }
        }

        drained.stream().map(this::makeFluidReservationToken).forEach(reserved::add);
        return Optional.of(StackUtil.mergeSameItems(reserved));
    }

    private ItemStack makeFluidReservationToken(FluidStack fluidStack) {
        ItemStack stack = FluidUtil.getFilledBucket(fluidStack);
        if (stack.isEmpty()) {
            stack = FluidUtilBC.getFragileFluid(fluidStack);
        }
        if (!stack.hasTag()) {
            stack.setTag(new CompoundTag());
        }
        // noinspection ConstantConditions
        stack.getTag().put(FLUID_STACK_KEY, fluidStack.writeToNBT(new CompoundTag()));
        return stack;
    }

    private void rollbackReservation(List<ItemStack> items, List<FluidStack> fluids) {
        items.forEach(this::refundReservedItem);
        fluids.forEach(this::queueFluidRefund);
    }

    private void refundReservedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack remainder = tile.getInvResources().insert(stack.copy(), false, false);
        if (!remainder.isEmpty() && !tile.getWorldBC().isClientSide) {
            InventoryUtil.addToBestAcceptor(tile.getWorldBC(), tile.getBuilderPos(), null, remainder);
        }
    }


    /**
     * Reserves one blueprint block by delegating to the batched robot-task reservation API.
     */
    public RobotBuildTask reserveNextRobotTask(EntityRobotBase robot, boolean needMaterial) {
        List<RobotBuildTask> tasks = reserveNextRobotTasks(robot, needMaterial, 1);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    public List<RobotBuildTask> reserveNextRobotTasks(EntityRobotBase robot, boolean needMaterial, int maxItems) {
        if (robot == null || getBuildingInfo() == null || checkResults == null || maxItems <= 0) {
            return Collections.emptyList();
        }

        List<RobotBuildTask> tasks = new ArrayList<>();
        int maxTasks = Math.max(1, maxItems);

        if (tile.canExcavate()) {
            for (int index : getBreakOrder()) {
                if (tasks.size() >= maxTasks) {
                    break;
                }
                BlockPos blockPos = indexToPos(index);
                if (!canRobotWorkAt(robot, blockPos)) {
                    continue;
                }
                check(blockPos);
                if (checkResults[index] != CHECK_RESULT_TO_BREAK) {
                    continue;
                }
                RobotBuildTask task = makeRobotBreakTask(robot, blockPos);
                if (task != null) {
                    tasks.add(task);
                }
            }
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        List<ItemStack> carried = new ArrayList<>();
        int carriedItems = 0;
        for (int index : getPlaceOrder()) {
            BlockPos blockPos = indexToPos(index);
            if (!canRobotWorkAt(robot, blockPos)) {
                continue;
            }
            check(blockPos);
            if (checkResults[index] != CHECK_RESULT_TO_PLACE || !canPlace(blockPos) || !isReadyToPlace(blockPos)) {
                continue;
            }
            RobotBuildTask task = makeRobotTask(robot, blockPos, needMaterial);
            if (task == null) {
                continue;
            }

            List<ItemStack> nextCarried = new ArrayList<>(carried.stream().map(ItemStack::copy).collect(Collectors.toList()));
            for (ItemStack stack : task.requirements()) {
                mergeRequirement(nextCarried, stack);
            }
            nextCarried = splitRequirements(nextCarried);
            int nextCarriedItems = nextCarried.stream().mapToInt(ItemStack::getCount).sum();
            if ((!tasks.isEmpty() && nextCarriedItems > maxItems) || nextCarried.size() > robot.getContainerSize()) {
                releaseRobotTask(robot, task);
                break;
            }
            if (nextCarriedItems > maxItems) {
                releaseRobotTask(robot, task);
                continue;
            }

            tasks.add(task);
            carried = nextCarried;
            carriedItems = nextCarriedItems;
            if (carriedItems >= maxItems || tasks.size() >= maxTasks) {
                break;
            }
        }
        return tasks;
    }

    private boolean canRobotWorkAt(EntityRobotBase robot, BlockPos blockPos) {
        return !blockPos.equals(tile.getBuilderPos())
            && (robot.getZoneToWork() == null || robot.getZoneToWork().contains(Vec3.atCenterOf(blockPos)))
            && !robotReservedBlocks.contains(blockPos)
            && (robot.getRegistry() == null || !robot.getRegistry().isTaken(new ResourceIdBlock(blockPos)));
    }

    private RobotBuildTask makeRobotBreakTask(EntityRobotBase robot, BlockPos blockPos) {
        GameProfile owner = getAutomationOwner(robot);
        if (BlockUtil.getFluidWithFlowing(tile.getWorldBC(), blockPos) != Fluids.EMPTY
            || BlockUtil.isUnbreakableBlock(tile.getWorldBC(), blockPos, owner)
            || !AutomationPermissionUtil.mayBlock(
                tile.getWorldBC(), tile.getBuilderPos(), blockPos, owner, AutomationPermissionUtil.SOURCE_ROBOT,
                WorldOperationKind.BLOCK_BREAK, OperationMode.SIMULATE
            )) {
            return null;
        }
        ResourceIdBlock resource = new ResourceIdBlock(blockPos);
        if (robot.getRegistry() != null && !robot.getRegistry().take(resource, robot)) {
            return null;
        }
        robotReservedBlocks.add(blockPos);
        return new RobotBuildTask(blockPos, Collections.emptyList(), computeRobotBreakEnergyCost(blockPos), true);
    }

    private RobotBuildTask makeRobotTask(EntityRobotBase robot, BlockPos blockPos, boolean needMaterial) {
        GameProfile owner = getAutomationOwner(robot);
        if (!AutomationPermissionUtil.mayBlock(
            tile.getWorldBC(), tile.getBuilderPos(), blockPos, owner, AutomationPermissionUtil.SOURCE_ROBOT,
            WorldOperationKind.BLOCK_PLACE, OperationMode.SIMULATE
        )) {
            return null;
        }
        int index = posToIndex(blockPos);
        List<FluidStack> requiredFluids = getBuildingInfo().toPlaceRequiredFluids[index];
        if (requiredFluids != null && requiredFluids.stream().anyMatch(stack -> stack != null && !stack.isEmpty())) {
            return null;
        }

        List<ItemStack> requirements = needMaterial
            ? StackUtil.mergeSameItems(Optional.ofNullable(getBuildingInfo().toPlaceRequiredItems[index])
                .orElseGet(java.util.Collections::emptyList)
                .stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::copy)
                .collect(Collectors.toList()))
            : java.util.Collections.emptyList();
        if (requirements.size() > robot.getContainerSize() || requirements.stream().anyMatch(stack -> stack.getCount() > stack.getMaxStackSize())) {
            return null;
        }

        ResourceIdBlock resource = new ResourceIdBlock(blockPos);
        if (robot.getRegistry() != null && !robot.getRegistry().take(resource, robot)) {
            return null;
        }
        robotReservedBlocks.add(blockPos);
        return new RobotBuildTask(blockPos, requirements, computeRobotEnergyCost(blockPos), false);
    }

    public void releaseRobotTask(EntityRobotBase robot, RobotBuildTask task) {
        if (task == null) {
            return;
        }
        robotReservedBlocks.remove(task.pos());
        if (robot != null && robot.getRegistry() != null) {
            robot.getRegistry().release(new ResourceIdBlock(task.pos()));
        }
    }

    public boolean buildRobotTask(EntityRobotBase robot, RobotBuildTask task) {
        return buildRobotTaskResult(robot, task) != RobotBuildResult.FAILED;
    }

    public RobotBuildResult buildRobotTaskResult(EntityRobotBase robot, RobotBuildTask task) {
        if (robot == null || task == null || getBuildingInfo() == null) {
            releaseRobotTask(robot, task);
            return RobotBuildResult.FAILED;
        }

        BlockPos blockPos = task.pos();
        try {
            if (task.breakTask()) {
                check(blockPos);
                if (checkResults[posToIndex(blockPos)] == CHECK_RESULT_CORRECT || tile.getWorldBC().isEmptyBlock(blockPos)) {
                    return RobotBuildResult.NOOP;
                }
                GameProfile owner = getAutomationOwner(robot);
                if (!tile.canExcavate()
                    || BlockUtil.getFluidWithFlowing(tile.getWorldBC(), blockPos) != Fluids.EMPTY
                    || BlockUtil.isUnbreakableBlock(tile.getWorldBC(), blockPos, owner)
                    || !AutomationPermissionUtil.mayBlock(
                        tile.getWorldBC(), tile.getBuilderPos(), blockPos, owner, AutomationPermissionUtil.SOURCE_ROBOT,
                        WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
                    )) {
                    return RobotBuildResult.FAILED;
                }
                Optional<List<ItemStack>> drops = tile.getWorldBC() instanceof ServerLevel serverLevel
                    ? BlockUtil.breakBlockAndGetDrops(
                        serverLevel,
                        blockPos,
                        new ItemStack(Items.DIAMOND_PICKAXE),
                        owner
                    )
                    : Optional.empty();
                boolean broken = drops.isPresent();
                if (broken) {
                    handleExcavationDrops(drops.get());
                }
                if (broken && check(blockPos)) {
                    afterChecks();
                }
                return broken ? RobotBuildResult.COMMITTED : RobotBuildResult.FAILED;
            }

            if (isBlockCorrect(blockPos)) {
                check(blockPos);
                return RobotBuildResult.NOOP;
            }
            check(blockPos);
            int index = posToIndex(blockPos);
            if (checkResults[index] != CHECK_RESULT_TO_PLACE || !canPlace(blockPos) || !isReadyToPlace(blockPos)) {
                return RobotBuildResult.FAILED;
            }
            if (!hasRobotRequirements(robot, task.requirements())) {
                return RobotBuildResult.FAILED;
            }
            ISchematicBlock schematicBlock = getSchematicBlock(blockPos);
            if (schematicBlock == null || schematicBlock.isAir()) {
                return RobotBuildResult.FAILED;
            }

            GameProfile owner = getAutomationOwner(robot);
            if (!AutomationPermissionUtil.mayBlock(
                tile.getWorldBC(), tile.getBuilderPos(), blockPos, owner, AutomationPermissionUtil.SOURCE_ROBOT,
                WorldOperationKind.BLOCK_PLACE, OperationMode.EXECUTE
            )) {
                return RobotBuildResult.FAILED;
            }
            Player actor = getAutomationPlayer(robot, blockPos);
            schematicBlock.build(tile.getWorldBC(), blockPos, actor);
            // Commit material consumption from the robot only after the requested schematic state actually exists.
            // A custom schematic handler may mutate the world before returning false, or return true without producing
            // the requested state; the world postcondition is the transaction boundary.
            boolean committed = isBlockCorrect(blockPos);
            if (committed) {
                consumeRobotRequirements(robot, task.requirements());
                if (check(blockPos)) {
                    afterChecks();
                }
            }
            return committed ? RobotBuildResult.COMMITTED : RobotBuildResult.FAILED;
        } finally {
            releaseRobotTask(robot, task);
        }
    }

    private boolean hasRobotRequirements(EntityRobotBase robot, List<ItemStack> requirements) {
        for (ItemStack requirement : requirements) {
            int found = 0;
            for (int slot = 0; slot < robot.getContainerSize(); slot++) {
                ItemStack stack = robot.getItem(slot);
                if (!stack.isEmpty() && StackUtil.canMerge(requirement, stack)) {
                    found += stack.getCount();
                    if (found >= requirement.getCount()) {
                        break;
                    }
                }
            }
            if (found < requirement.getCount()) {
                return false;
            }
        }
        return true;
    }

    private void consumeRobotRequirements(EntityRobotBase robot, List<ItemStack> requirements) {
        for (ItemStack requirement : requirements) {
            int left = requirement.getCount();
            for (int slot = 0; slot < robot.getContainerSize() && left > 0; slot++) {
                ItemStack stack = robot.getItem(slot);
                if (!stack.isEmpty() && StackUtil.canMerge(requirement, stack)) {
                    int used = Math.min(left, stack.getCount());
                    robot.removeItem(slot, used);
                    left -= used;
                }
            }
        }
    }

    private void mergeRequirement(List<ItemStack> merged, ItemStack requirement) {
        if (requirement == null || requirement.isEmpty()) {
            return;
        }
        for (ItemStack existing : merged) {
            if (ItemStack.isSameItemSameTags(existing, requirement)) {
                existing.grow(requirement.getCount());
                return;
            }
        }
        merged.add(requirement.copy());
    }

    private List<ItemStack> splitRequirements(List<ItemStack> merged) {
        List<ItemStack> split = new ArrayList<>();
        for (ItemStack stack : merged) {
            int left = stack.getCount();
            int limit = Math.max(1, stack.getMaxStackSize());
            while (left > 0) {
                ItemStack copy = stack.copy();
                copy.setCount(Math.min(limit, left));
                split.add(copy);
                left -= copy.getCount();
            }
        }
        return split;
    }

    private int computeRobotEnergyCost(BlockPos blockPos) {
        return Math.max(8, (int) Math.ceil(Math.sqrt(blockPos.distSqr(tile.getBuilderPos())) * 10.0D));
    }

    private int computeRobotBreakEnergyCost(BlockPos blockPos) {
        long breakMj = Math.max(1L, BlockUtil.computeBlockBreakPower(tile.getWorldBC(), blockPos) / MjAmount.MICRO_MJ_PER_MJ);
        return Math.max(16, computeRobotEnergyCost(blockPos) + (int) Math.min(10_000L, breakMj));
    }

    public enum RobotBuildResult {
        COMMITTED,
        NOOP,
        FAILED
    }

    public static class RobotBuildTask {
        private final BlockPos pos;
        private final List<ItemStack> requirements;
        private final int energyCost;
        private final boolean breakTask;

        public RobotBuildTask(BlockPos pos, List<ItemStack> requirements, int energyCost) {
            this(pos, requirements, energyCost, false);
        }

        public RobotBuildTask(BlockPos pos, List<ItemStack> requirements, int energyCost, boolean breakTask) {
            this.pos = pos;
            this.requirements = ImmutableList.copyOf(requirements == null ? java.util.Collections.emptyList() : requirements);
            this.energyCost = energyCost;
            this.breakTask = breakTask;
        }

        public RobotBuildTask(CompoundTag nbt) {
            this.pos = NbtUtils.readBlockPos(nbt.getCompound("pos"));
            this.requirements = ImmutableList.copyOf(
                NBTUtilBC.readCompoundList(nbt.get("requirements"))
                    .map(ItemStack::of)
                    .collect(Collectors.toList())
            );
            this.energyCost = nbt.getInt("energyCost");
            this.breakTask = nbt.getBoolean("breakTask");
        }

        public BlockPos pos() {
            return pos;
        }

        public List<ItemStack> requirements() {
            return requirements;
        }

        public int energyCost() {
            return energyCost;
        }

        public boolean breakTask() {
            return breakTask;
        }

        public CompoundTag writeToNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.put("pos", NbtUtils.writeBlockPos(pos));
            nbt.put("requirements", NBTUtilBC.writeObjectList(requirements.stream().map(ItemStack::serializeNBT)));
            nbt.putInt("energyCost", energyCost);
            nbt.putBoolean("breakTask", breakTask);
            return nbt;
        }
    }

    @Override
    protected boolean canPlace(BlockPos blockPos) {
        // noinspection ConstantConditions
        return !isAir(blockPos) && getSchematicBlock(blockPos).canBuild(tile.getWorldBC(), blockPos);
    }

    @Override
    protected boolean isReadyToPlace(BlockPos blockPos) {
        // noinspection ConstantConditions
        return getSchematicBlock(blockPos).getRequiredBlockOffsets().stream()
            .map(blockPos::offset)
            .allMatch(pos -> getSchematicBlock(pos) == null || checkResults[posToIndex(pos)] == CHECK_RESULT_CORRECT) &&
            getSchematicBlock(blockPos).isReadyToBuild(tile.getWorldBC(), blockPos);
    }

    @Override
    protected boolean hasEnoughToPlaceItems(BlockPos blockPos) {
        return !tile.needMeterial() || tryExtractRequired(
            getBuildingInfo().toPlaceRequiredItems[posToIndex(blockPos)],
            getBuildingInfo().toPlaceRequiredFluids[posToIndex(blockPos)],
            true
        ).isPresent();
    }

    @Override
    protected List<ItemStack> getToPlaceItems(BlockPos blockPos) {
        return tile.needMeterial() ? tryExtractRequired(
            getBuildingInfo().toPlaceRequiredItems[posToIndex(blockPos)],
            getBuildingInfo().toPlaceRequiredFluids[posToIndex(blockPos)],
            false
        ).orElse(null) : Stream.concat(getBuildingInfo().toPlaceRequiredItems[posToIndex(blockPos)].stream(), 
        		getBuildingInfo().toPlaceRequiredFluids[posToIndex(blockPos)].stream().map(t -> new ItemStack(t.getFluid().getBucket()))).collect(Collectors.toList());
    }

    @Override
    protected void cancelPlaceTask(PlaceTask placeTask) {
        super.cancelPlaceTask(placeTask);
        // In creative/no-material mode PlaceTask.items are display-only requirements; nothing was extracted.
        // Refunding them would mint free items/fluids whenever a placement is cancelled.
        if (!tile.needMeterial() || placeTask.items == null) {
            return;
        }
        placeTask.items.stream()
            .filter(stack -> !stack.hasTag() || !stack.getTag().contains(FLUID_STACK_KEY))
            .forEach(this::refundReservedItem);
        placeTask.items.stream()
            .filter(stack -> stack.hasTag() && stack.getTag().contains(FLUID_STACK_KEY))
            .map(stack -> Pair.of(stack.getCount(), stack.getTag().getCompound(FLUID_STACK_KEY)))
            .map(countNbt -> {
                FluidStack fluidStack = FluidStack.loadFluidStackFromNBT(countNbt.getRight());
                if (fluidStack != null) {
                    fluidStack.setAmount(fluidStack.getAmount() * countNbt.getLeft());
                }
                return fluidStack;
            })
            .filter(Objects::nonNull)
            .filter(fluidStack -> !fluidStack.isEmpty())
            .forEach(this::queueFluidRefund);
    }

    private void queueFluidRefund(FluidStack fluidStack) {
        FluidStack remainder = fluidStack.copy();
        int accepted = tile.getTankManager().fill(remainder, FluidAction.EXECUTE);
        remainder.shrink(accepted);
        if (!remainder.isEmpty()) {
            pendingFluidRefunds.add(remainder);
            markTileDirty();
        }
    }

    private boolean flushPendingFluidRefunds() {
        for (java.util.Iterator<FluidStack> iterator = pendingFluidRefunds.iterator(); iterator.hasNext();) {
            FluidStack pending = iterator.next();
            int accepted = tile.getTankManager().fill(pending, FluidAction.EXECUTE);
            pending.shrink(accepted);
            if (accepted > 0) {
                markTileDirty();
            }
            if (pending.isEmpty()) {
                iterator.remove();
            }
        }
        return pendingFluidRefunds.isEmpty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag nbt = super.serializeNBT();
        nbt.put("pendingFluidRefunds", NBTUtilBC.writeObjectList(
            pendingFluidRefunds.stream().map(stack -> stack.writeToNBT(new CompoundTag()))
        ));
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        pendingFluidRefunds.clear();
        NBTUtilBC.readCompoundList(nbt.get("pendingFluidRefunds"))
            .map(FluidStack::loadFluidStackFromNBT)
            .filter(Objects::nonNull)
            .filter(stack -> !stack.isEmpty())
            .forEach(pendingFluidRefunds::add);
        super.deserializeNBT(nbt);
    }

    @Override
    protected boolean isBlockCorrect(BlockPos blockPos) {
        // noinspection ConstantConditions
        return getBuildingInfo() != null &&
            getSchematicBlock(blockPos) != null &&
            getSchematicBlock(blockPos).isBuilt(tile.getWorldBC(), blockPos);
    }

    @Override
    protected boolean doPlaceTask(PlaceTask placeTask) {
        if (getBuildingInfo() == null || getSchematicBlock(placeTask.pos) == null) {
            return false;
        }
        if (!AutomationPermissionUtil.mayBlock(
            tile.getWorldBC(), tile.getBuilderPos(), placeTask.pos, tile.getOwner(),
            AutomationPermissionUtil.SOURCE_BUILDER, WorldOperationKind.BLOCK_PLACE, OperationMode.EXECUTE
        )) {
            return false;
        }
        Player actor = getAutomationPlayer(null, placeTask.pos);
        return getSchematicBlock(placeTask.pos).build(tile.getWorldBC(), placeTask.pos, actor);
    }

    private GameProfile getAutomationOwner(EntityRobotBase robot) {
        if (robot instanceof EntityRobot entityRobot) {
            return entityRobot.getOwnerProfile();
        }
        return tile.getOwner();
    }

    private Player getAutomationPlayer(EntityRobotBase robot, BlockPos pos) {
        if (!(tile.getWorldBC() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, getAutomationOwner(robot), pos);
    }

    private boolean processDeferredInventoryContents() {
        if (remainingDeferredRequiredBlocks == null || remainingDeferredRequiredBlocks.length == 0) {
            return true;
        }

        Level level = tile.getWorldBC();
        int scans = Math.min(DEFERRED_INVENTORY_SCAN_BUDGET, remainingDeferredRequiredBlocks.length);
        int insertedStacks = 0;
        boolean changed = false;

        for (int scan = 0; scan < scans; scan++) {
            int index = deferredInventoryCursor;
            deferredInventoryCursor = (deferredInventoryCursor + 1) % remainingDeferredRequiredBlocks.length;

            List<ItemStack> remaining = remainingDeferredRequiredBlocks[index];
            if (remaining == null || remaining.isEmpty()) {
                continue;
            }

            BlockPos blockPos = indexToPos(index);
            ISchematicBlock schematicBlock = getSchematicBlock(index);
            if (!schematicBlock.isBuilt(level, blockPos)) {
                continue;
            }

            List<ItemStack> reconciled = retainStillMissing(
                remaining,
                schematicBlock.computeMissingDeferredRequiredItems(level, blockPos)
            );
            if (!sameStackCounts(remaining, reconciled)) {
                remainingDeferredRequiredBlocks[index] = reconciled;
                remaining = reconciled;
                changed = true;
            }
            if (remaining.isEmpty() || insertedStacks >= DEFERRED_INVENTORY_INSERT_BUDGET) {
                continue;
            }

            ItemStack wanted = remaining.get(0).copy();
            wanted.setCount(Math.min(wanted.getCount(), wanted.getMaxStackSize()));
            ItemStack simulatedRemainder = schematicBlock.insertDeferredItem(level, blockPos, wanted, true);
            int accepted = wanted.getCount() - simulatedRemainder.getCount();
            if (accepted <= 0) {
                continue;
            }

            ItemStack toInsert;
            if (tile.needMeterial()) {
                toInsert = tile.getInvResources().extract(
                    extracted -> StackUtil.canMerge(wanted, extracted),
                    1,
                    accepted,
                    false
                );
                if (toInsert.isEmpty()) {
                    continue;
                }
            } else {
                toInsert = wanted.copy();
                toInsert.setCount(accepted);
            }

            ItemStack overflow = schematicBlock.insertDeferredItem(level, blockPos, toInsert, false);
            int inserted = toInsert.getCount() - overflow.getCount();
            if (!overflow.isEmpty() && tile.needMeterial()) {
                // The target inventory may have changed between simulation and execution. Never delete the remainder.
                refundReservedItem(overflow);
            }
            if (inserted > 0) {
                removeInserted(remaining, toInsert, inserted);
                insertedStacks++;
                changed = true;
            }
        }

        if (changed) {
            updateDeferredRequiredConcat();
        }
        return !hasPendingDeferredItems();
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return new ArrayList<>();
        }
        return stacks.stream()
            .filter(Objects::nonNull)
            .filter(stack -> !stack.isEmpty())
            .map(ItemStack::copy)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<ItemStack> retainStillMissing(List<ItemStack> remaining, List<ItemStack> missing) {
        List<ItemStack> availableMissing = copyStacks(missing);
        List<ItemStack> reconciled = new ArrayList<>();
        for (ItemStack pending : remaining) {
            int count = pending.getCount();
            int stillMissing = 0;
            for (ItemStack candidate : availableMissing) {
                if (count <= 0) {
                    break;
                }
                if (ItemStack.isSameItemSameTags(pending, candidate)) {
                    int used = Math.min(count, candidate.getCount());
                    count -= used;
                    stillMissing += used;
                    candidate.shrink(used);
                }
            }
            if (stillMissing > 0) {
                ItemStack copy = pending.copy();
                copy.setCount(stillMissing);
                reconciled.add(copy);
            }
        }
        return reconciled;
    }

    private static boolean sameStackCounts(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            ItemStack a = first.get(i);
            ItemStack b = second.get(i);
            if (a.getCount() != b.getCount() || !ItemStack.isSameItemSameTags(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static void removeInserted(List<ItemStack> remaining, ItemStack insertedStack, int inserted) {
        for (java.util.Iterator<ItemStack> iterator = remaining.iterator(); iterator.hasNext() && inserted > 0; ) {
            ItemStack pending = iterator.next();
            if (!ItemStack.isSameItemSameTags(pending, insertedStack)) {
                continue;
            }
            int used = Math.min(inserted, pending.getCount());
            pending.shrink(used);
            inserted -= used;
            if (pending.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private boolean hasPendingDeferredItems() {
        return remainingDeferredRequiredBlocks != null && Arrays.stream(remainingDeferredRequiredBlocks)
            .filter(Objects::nonNull)
            .anyMatch(stacks -> !stacks.isEmpty());
    }

    private void updateDeferredRequiredConcat() {
        if (remainingDeferredRequiredBlocks == null) {
            remainingDeferredRequiredBlocksConcat = Collections.emptyList();
            return;
        }
        remainingDeferredRequiredBlocksConcat = StackUtil.mergeSameItems(
            Arrays.stream(remainingDeferredRequiredBlocks)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(ItemStack::copy)
                .collect(Collectors.toList())
        );
    }

    @Override
	public boolean tick() {
    	Level level = tile.getWorldBC();
    	
        if (level.isClientSide) {
            return super.tick();
        }
        if (!flushPendingFluidRefunds()) {
            return false;
        }
        level.getProfiler().push("entitiesWithinBox");
        List<Entity> entitiesWithinBox = level.getEntitiesOfClass(
            Entity.class,
            getBuildingInfo().box.getBoundingBox(),
            Objects::nonNull
        );
        level.getProfiler().pop();
        level.getProfiler().push("toSpawn");
        List<ISchematicEntity> toSpawn = getBuildingInfo().entities.stream()
            .filter(schematicEntity ->
                entitiesWithinBox.stream()
                .noneMatch($ -> 
                	$.distanceToSqr(schematicEntity.getPos().add(Vec3.atLowerCornerOf(getBuildingInfo().offsetPos)))< MAX_ENTITY_DISTANCE
                )
/*                    .map(Entity::)
                    .map(schematicEntity.getPos().add(new Vec3(getBuildingInfo().offsetPos))::distanceTo)
                    .noneMatch(distance -> distance < MAX_ENTITY_DISTANCE)*/
            )
            .collect(Collectors.toList());
        level.getProfiler().pop();
        // Compute needed stacks
        level.getProfiler().push("remainingDisplayRequired");
        remainingDisplayRequired.clear();
        remainingDisplayRequired.addAll(StackUtil.mergeSameItems(
            Stream.concat(
                Stream.concat(
                    remainingDisplayRequiredBlocksConcat.stream(),
                    remainingDeferredRequiredBlocksConcat.stream()
                ),
                toSpawn.stream()
                    .flatMap(schematicEntity ->
                        getDisplayRequired(
                            getBuildingInfo().entitiesRequiredItems.get(schematicEntity),
                            getBuildingInfo().entitiesRequiredFluids.get(schematicEntity)
                        )
                    )
            ).collect(Collectors.toList())
        ));
        level.getProfiler().pop();
        // Kill not needed entities
        level.getProfiler().push("toKill");
        List<Entity> toKill = entitiesWithinBox.stream()
            .filter(entity ->
                entity != null &&
                    getBuildingInfo().entities.stream()
                    	.noneMatch($ -> 
                    	entity.distanceToSqr(Vec3.atLowerCornerOf(
                    			getBuildingInfo().offsetPos)
                    			.add($.getPos()))
                    			< MAX_ENTITY_DISTANCE)&&
/*                        .map(ISchematicEntity::getPos)
                        .map(Vec3.atLowerCornerOf(getBuildingInfo().offsetPos)::add)
                        .map(entity::distanceToSqr)
/                       .noneMatch(distance -> distance < MAX_ENTITY_DISTANCE)&&*/
                    SchematicEntityManager.getSchematicEntity(new SchematicEntityContext(
                        level,
                        BlockPos.ZERO,
                        entity
                    )) != null
            )
            .collect(Collectors.toList());
        if (!toKill.isEmpty()) {
            if (!tile.getBattery().isFull()) {
                return false;
            } else {
                level.getProfiler().push("kill");
                toKill.forEach(Entity::kill);
                level.getProfiler().pop();
            }
        }
        level.getProfiler().pop();
        // Build the structure and fill already placed inventories independently. Inventory contents must not block
        // placement of the block itself, and may arrive over multiple ticks as resources become available.
        boolean blocksDone = super.tick();
        boolean inventoriesDone = processDeferredInventoryContents();
        if (!blocksDone || !inventoriesDone) {
            return false;
        }

        // Spawn needed entities. Missing resources or failed entity placement must keep the builder active;
        // otherwise the tile marks the blueprint as finished and never retries item frames / armor stands.
        if (toSpawn.isEmpty()) {
            return true;
        }
        if (!tile.getBattery().isFull()) {
            return false;
        }

        boolean spawnedAll = true;
        level.getProfiler().push("spawn");
        for (ISchematicEntity schematicEntity : toSpawn) {
            List<ItemStack> requiredItems = getBuildingInfo().entitiesRequiredItems.get(schematicEntity);
            List<FluidStack> requiredFluids = getBuildingInfo().entitiesRequiredFluids.get(schematicEntity);
            if (!tile.needMeterial()) {
                buildSchematicEntity(schematicEntity, level, getBuildingInfo().offsetPos);
                if (!isSchematicEntityPresent(schematicEntity, level)) {
                    spawnedAll = false;
                }
                continue;
            }

            Optional<List<ItemStack>> extracted = tryExtractRequired(requiredItems, requiredFluids, false);
            if (!extracted.isPresent()) {
                spawnedAll = false;
                continue;
            }
            buildSchematicEntity(schematicEntity, level, getBuildingInfo().offsetPos);
            if (!isSchematicEntityPresent(schematicEntity, level)) {
                // Entity resources commit on the world postcondition, not on a handler's return value. This avoids
                // free entities when a custom handler spawns first and reports failure afterwards.
                cancelPlaceTask(new PlaceTask(tile.getBuilderPos(), extracted.get(), 0));
                spawnedAll = false;
                continue;
            }
        }
        level.getProfiler().pop();
        return spawnedAll;
    }

    private boolean isSchematicEntityPresent(ISchematicEntity schematicEntity, Level level) {
        Vec3 expected = schematicEntity.getPos().add(Vec3.atLowerCornerOf(getBuildingInfo().offsetPos));
        return level.getEntitiesOfClass(Entity.class, getBuildingInfo().box.getBoundingBox(), Objects::nonNull).stream()
            .anyMatch(entity -> entity.distanceToSqr(expected) < MAX_ENTITY_DISTANCE);
    }

    private static boolean buildSchematicEntity(ISchematicEntity schematicEntity, Level level, BlockPos basePos) {
        if (schematicEntity instanceof Api2SchematicEntity api2) {
            return api2.place(level, basePos);
        }
        return schematicEntity.build(level, basePos) != null;
    }

    @Override
    protected boolean check(BlockPos blockPos) {
        if (super.check(blockPos)) {
            remainingDisplayRequiredBlocks[posToIndex(blockPos)] =
                checkResults[posToIndex(blockPos)] != CHECK_RESULT_CORRECT
                    ?
                    getDisplayRequired(
                        getBuildingInfo().toPlaceRequiredItems[posToIndex(blockPos)],
                        getBuildingInfo().toPlaceRequiredFluids[posToIndex(blockPos)]
                    ).collect(Collectors.toList())
                    : Collections.emptyList();
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected void afterChecks() {
        remainingDisplayRequiredBlocksConcat = StackUtil.mergeSameItems(
            Arrays.stream(remainingDisplayRequiredBlocks)
                .flatMap(Collection::stream)
                .collect(Collectors.toList())
        );
    }

    @Override
    public void writeToByteBuf(FriendlyByteBuf buffer) {
        super.writeToByteBuf(buffer);
        buffer.writeInt(remainingDisplayRequired.size());
        remainingDisplayRequired.forEach(stack -> {
            buffer.writeItem(stack);
            buffer.writeInt(stack.getCount());
        });
    }

    @Override
    public void readFromByteBuf(FriendlyByteBuf buffer) {
        super.readFromByteBuf(buffer);
        remainingDisplayRequired.clear();
        IntStream.range(0, buffer.readInt()).mapToObj(i -> {
            ItemStack stack;
            stack = buffer.readItem();
            stack.setCount(buffer.readInt());
            return stack;
        }).forEach(remainingDisplayRequired::add);
    }
}
