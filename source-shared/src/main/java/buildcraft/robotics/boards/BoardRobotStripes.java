package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.ai.AIRobotStripesHandler;
import buildcraft.robotics.scheduler.RobotAreaActionScheduler;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Classic BuildCraft Stripes robot: fetches an item and applies the Stripes pipe item-use handlers to a free block. */
public class BoardRobotStripes extends RedstoneBoardRobot {
    private BlockIndex blockFound;
    private BlockPos processorTarget;
    private long processorToken;
    /** A cancelled managed request must never become an unclaimed item use. */
    private boolean managedItemUse;

    public BoardRobotStripes(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("stripes").nbt();
    }

    @Override
    public void update() {
        // When unlinked, a formerly managed worker must immediately regain
        // ordinary autonomous item-use semantics; its old transient claim is
        // not permission to use the abandoned panel target.
        boolean managed = RobotAreaActionScheduler.hasController(robot);
        if (!managed && managedItemUse) {
            finishProcessorTarget(false);
            releaseBlockFound();
            managedItemUse = false;
        }
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, new AnyUsableStackFilter()));
            return;
        }

        if (blockFound != null) {
            startDelegateAI(new AIRobotStripesHandler(robot, blockFound));
            return;
        }

        if (managed) {
            RobotAreaActionScheduler.Assignment assigned = RobotAreaActionScheduler.acquire(robot);
            if (assigned == null) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            } else {
                processorTarget = assigned.target();
                processorToken = assigned.token();
                managedItemUse = true;
                // Search/pick an actual navigable face; never use the old
                // independent full-air scanner for a managed Stripes robot.
                startDelegateAI(new AIRobotSearchAndGotoBlock(robot, true,
                        this::isFreeTargetBlock, java.util.List.of(processorTarget)));
            }
        } else {
            startDelegateAI(new AIRobotSearchAndGotoBlock(robot, true, this::isFreeTargetBlock));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchAndGotoBlock search) {
            if (search.success()) {
                blockFound = search.getBlockFound();
                startDelegateAI(new AIRobotStripesHandler(robot, blockFound));
            } else {
                finishProcessorTarget(false);
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotFetchAndEquipItemStack) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotStripesHandler) {
            finishProcessorTarget(ai.success());
            releaseBlockFound();
            if (!ai.success() && !robot.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
                startDelegateAI(new AIRobotGotoStationAndUnload(robot));
            }
        } else if (ai instanceof AIRobotGotoStationAndUnload) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoSleep) {
            terminate();
        }
    }

    /** A candidate is just empty space; do not scan/store the entire air volume. */
    public boolean processorHasItem() {
        return !robot.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty();
    }

    public boolean processorCanUseAt(BlockPos pos) {
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty() || !isFreeTargetBlock(robot.getCommandSenderWorld(), pos)) return false;
        var zone = robot.getZoneToWork();
        if (zone != null && !zone.contains(new Vec3(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D)))
            return false;
        return RobotAutomationSupport.permitsBlock(robot, robot.getCommandSenderWorld(), pos,
                WorldOperationKind.ITEM_USE, OperationMode.SIMULATE)
                && (!(held.getItem() instanceof BlockItem) || RobotAutomationSupport.permitsBlock(
                        robot, robot.getCommandSenderWorld(), pos,
                        WorldOperationKind.BLOCK_PLACE, OperationMode.SIMULATE));
    }

    public boolean processorAllowsExecution(BlockPos pos) {
        return !managedItemUse || (processorToken > 0 && pos != null
                && pos.equals(processorTarget)
                && RobotAreaActionScheduler.owns(robot, pos, processorToken));
    }

    @Override
    public void delegateAIAborted(AIRobot ai) {
        finishProcessorTarget(false);
        releaseBlockFound();
    }

    private void finishProcessorTarget(boolean completed) {
        if (processorToken > 0 && processorTarget != null) {
            RobotAreaActionScheduler.finished(robot, processorTarget, processorToken, completed);
        }
        processorTarget = null;
        processorToken = 0;
        // The handler might still be running when a Gate aborts/unloads its
        // parent. Do not turn a released managed claim into a free action.
        if (completed) managedItemUse = false;
    }

    private boolean isFreeTargetBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isEmptyBlock(pos)) {
            return false;
        }
        return robot.getRegistry() == null || !robot.getRegistry().isTaken(new ResourceIdBlock(pos));
    }

    private void releaseBlockFound() {
        if (blockFound != null) {
            if (robot.getRegistry() != null) {
                robot.getRegistry().release(new ResourceIdBlock(blockFound));
            }
            blockFound = null;
        }
    }

    @Override
    public void end() {
        finishProcessorTarget(false);
        releaseBlockFound();
    }

    @Override
    public void onUnload() {
        if (managedItemUse) {
            finishProcessorTarget(false);
            releaseBlockFound();
        }
        super.onUnload();
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockFound != null) {
            CompoundTag tag = new CompoundTag();
            blockFound.writeTo(tag);
            nbt.put("blockFound", tag);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockFound")) {
            blockFound = new BlockIndex(nbt.getCompound("blockFound"));
        }
    }

    private static class AnyUsableStackFilter implements IStackFilter {
        @Override
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty();
        }
    }
}
