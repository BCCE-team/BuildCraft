package buildcraft.robotics.boards;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildResult;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
import buildcraft.builders.tile.IRobotBuilderTarget;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.lib.inventory.filter.ArrayStackOrListFilter;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.ai.AIRobotStraightMoveTo;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoad;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotRecharge;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;

/**
 * BuildCraft 7 style Builder robot.
 * <p>
 * It can reserve blueprint positions from nearby Builder blocks and from classic Construction Markers, then performs the
 * same fetch -> fly -> build loop used by BuildCraft 7.
 */
public class BoardRobotBuilder extends RedstoneBoardRobot {
    private static final int MAX_RANGE_SQ = 3 * 64 * 64;
    private static final int RETRY_DELAY = 40;
    /** Classic builder robots should shuttle a useful batch, not one block at a time. */
    private static final int MAX_CARRIED_ITEMS = 128;

    private BlockPos builderPos;
    private final List<RobotBuildTask> currentTasks = new ArrayList<>();
    private LinkedList<ItemStack> requirementsToLookFor;
    private int launchingDelay;

    public BoardRobotBuilder(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("builder").nbt();
    }

    @Override
    public void update() {
        if (launchingDelay > 0) {
            launchingDelay--;
            return;
        }

        IRobotBuilderTarget builder = getTargetBuilder();
        if (currentTasks.isEmpty()) {
            if (robot.containsItems()) {
                startDelegateAI(new AIRobotGotoStationAndUnload(robot));
                return;
            }
            builder = reserveClosestTasks();
            if (builder == null || currentTasks.isEmpty()) {
                launchingDelay = RETRY_DELAY;
                startDelegateAI(new AIRobotGotoSleep(robot));
                return;
            }
            requirementsToLookFor = null;
        }

        if (builder == null || !builder.canRobotsBuild()) {
            releaseCurrentTasks();
            launchingDelay = RETRY_DELAY;
            startDelegateAI(new AIRobotGotoSleep(robot));
            return;
        }

        requirementsToLookFor = new LinkedList<>(getMissingRequirements(currentTasks));
        if (!requirementsToLookFor.isEmpty()) {
            ItemStack stack = requirementsToLookFor.getFirst();
            startDelegateAI(new AIRobotGotoStationAndLoad(robot, new ArrayStackOrListFilter(stack), stack.getCount()));
            return;
        }

        RobotBuildTask task = currentTasks.get(0);
        if (robot.getEnergy() - task.energyCost() <= EntityRobotBase.SAFETY_ENERGY) {
            startDelegateAI(new AIRobotRecharge(robot));
            return;
        }

        BlockPos pos = task.pos();
        startDelegateAI(new AIRobotStraightMoveTo(robot, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationAndLoad) {
            if (ai.success()) {
                // Recompute the complete missing-list on the next tick because one requirement may be split across
                // multiple inventory stacks and every split must be satisfied.
                requirementsToLookFor = null;
            } else {
                releaseCurrentTasks();
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoBlock || ai instanceof AIRobotStraightMoveTo) {
            IRobotBuilderTarget builder = getTargetBuilder();
            if (!ai.success() || builder == null || currentTasks.isEmpty()) {
                releaseCurrentTasks();
                startDelegateAI(new AIRobotGotoSleep(robot));
                return;
            }
            RobotBuildTask task = currentTasks.get(0);
            if (robot.getEnergy() - task.energyCost() <= EntityRobotBase.SAFETY_ENERGY) {
                startDelegateAI(new AIRobotRecharge(robot));
                return;
            }

            RobotBuildResult result = builder.buildRobotTaskResult(robot, task);
            currentTasks.remove(0);
            if (result != RobotBuildResult.FAILED) {
                if (result == RobotBuildResult.COMMITTED) {
                    robot.getBattery().extractPower(task.energyCost(), task.energyCost());
                }
                launchingDelay = currentTasks.isEmpty() ? Math.max(8, task.requirements().size() * 10) : 0;
                requirementsToLookFor = null;
            } else {
                launchingDelay = RETRY_DELAY;
                releaseCurrentTasks();
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoStationAndUnload) {
            if (!ai.success() && robot.containsItems()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotRecharge) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoSleep) {
            terminate();
        }
    }

    @Override
    public void end() {
        releaseCurrentTasks();
    }

    private IRobotBuilderTarget reserveClosestTasks() {
        List<IRobotBuilderTarget> builders = new ArrayList<>();
        builders.addAll(TileBuilder.getLoadedBuilders());
        builders.addAll(TileConstructionMarker.getLoadedMarkers());
        //? if <1.20 {
        builders.removeIf(builder -> builder == null || builder.getLevel() != robot.level || !builder.canRobotsBuild()
        //?} else {
        /*?
        builders.removeIf(builder -> builder == null || builder.getLevel() != robot.level() || !builder.canRobotsBuild()
        ?*/
        //?}
            || robot.blockPosition().distSqr(builder.getBlockPos()) > MAX_RANGE_SQ);
        builders.sort(Comparator.comparingDouble(builder -> robot.blockPosition().distSqr(builder.getBlockPos())));

        for (IRobotBuilderTarget builder : builders) {
            // The work zone constrains task positions inside BlueprintBuilder, not the Builder/station itself.
            List<RobotBuildTask> tasks = builder.reserveRobotBuildTasks(robot, MAX_CARRIED_ITEMS);
            if (!tasks.isEmpty()) {
                builderPos = builder.getBlockPos();
                currentTasks.clear();
                currentTasks.addAll(tasks);
                return builder;
            }
        }
        return null;
    }

    private IRobotBuilderTarget getTargetBuilder() {
        //? if <1.20 {
        if (builderPos == null || robot.level == null || !robot.level.isLoaded(builderPos)) {
        //?} else {
        /*?
        if (builderPos == null || robot.level() == null || !robot.level().isLoaded(builderPos)) {
        ?*/
        //?}
            return null;
        }
        //? if <1.20 {
        return robot.level.getBlockEntity(builderPos) instanceof IRobotBuilderTarget builder ? builder : null;
        //?} else {
        /*?
        return robot.level().getBlockEntity(builderPos) instanceof IRobotBuilderTarget builder ? builder : null;
        ?*/
        //?}
    }

    private void releaseCurrentTasks() {
        if (!currentTasks.isEmpty()) {
            IRobotBuilderTarget builder = getTargetBuilder();
            for (RobotBuildTask task : currentTasks) {
                if (builder != null) {
                    builder.releaseRobotBuildTask(robot, task);
                } else if (robot.getRegistry() != null) {
                    robot.getRegistry().release(new ResourceIdBlock(task.pos()));
                }
            }
        }
        currentTasks.clear();
        requirementsToLookFor = null;
        builderPos = null;
    }

    private List<ItemStack> getMissingRequirements(List<RobotBuildTask> tasks) {
        List<ItemStack> required = getMergedRequirements(tasks);
        List<ItemStack> missing = new ArrayList<>();

        for (ItemStack requirement : required) {
            if (requirement == null || requirement.isEmpty()) {
                continue;
            }

            int left = requirement.getCount();
            for (int slot = 0; slot < robot.getContainerSize() && left > 0; slot++) {
                ItemStack stack = robot.getItem(slot);
                if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, requirement)) {
                    left -= Math.min(left, stack.getCount());
                }
            }

            while (left > 0) {
                ItemStack copy = requirement.copy();
                copy.setCount(Math.min(Math.min(copy.getMaxStackSize(), MAX_CARRIED_ITEMS), left));
                missing.add(copy);
                left -= copy.getCount();
            }
        }

        return missing;
    }

    private List<ItemStack> getAggregatedRequirements(List<RobotBuildTask> tasks) {
        return splitRequirements(getMergedRequirements(tasks));
    }

    private List<ItemStack> getMergedRequirements(List<RobotBuildTask> tasks) {
        List<ItemStack> merged = new ArrayList<>();
        for (RobotBuildTask task : tasks) {
            for (ItemStack requirement : task.requirements()) {
                mergeRequirement(merged, requirement);
            }
        }
        return merged;
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
        return merged.stream()
            .flatMap(stack -> {
                List<ItemStack> split = new ArrayList<>();
                int left = Math.min(stack.getCount(), MAX_CARRIED_ITEMS);
                int limit = Math.max(1, stack.getMaxStackSize());
                while (left > 0) {
                    ItemStack copy = stack.copy();
                    copy.setCount(Math.min(limit, left));
                    split.add(copy);
                    left -= copy.getCount();
                }
                return split.stream();
            })
            .collect(Collectors.toList());
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        super.writeSelfToNBT(nbt);
        nbt.putInt("launchingDelay", launchingDelay);
        if (builderPos != null) {
            nbt.put("builderPos", NbtUtils.writeBlockPos(builderPos));
        }
        if (!currentTasks.isEmpty()) {
            nbt.put("currentTasks", NBTUtilBC.writeObjectList(currentTasks.stream().map(RobotBuildTask::writeToNBT)));
        }
        if (requirementsToLookFor != null) {
            nbt.put("requirementsToLookFor", NBTUtilBC.writeObjectList(requirementsToLookFor.stream().map(ItemStack::serializeNBT)));
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        super.loadSelfFromNBT(nbt);
        launchingDelay = nbt.getInt("launchingDelay");
        if (nbt.contains("builderPos")) {
            builderPos = NbtUtils.readBlockPos(nbt.getCompound("builderPos"));
        }
        currentTasks.clear();
        if (nbt.contains("currentTasks")) {
            NBTUtilBC.readCompoundList(nbt.get("currentTasks"))
                .map(RobotBuildTask::new)
                .forEach(currentTasks::add);
        } else if (nbt.contains("currentTask")) {
            currentTasks.add(new RobotBuildTask(nbt.getCompound("currentTask")));
        }
        if (nbt.contains("requirementsToLookFor")) {
            requirementsToLookFor = new LinkedList<>(
                NBTUtilBC.readCompoundList(nbt.get("requirementsToLookFor"))
                    .map(ItemStack::of)
                    .toList()
            );
        }
    }
}
