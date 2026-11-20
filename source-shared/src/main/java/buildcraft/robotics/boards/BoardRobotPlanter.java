package buildcraft.robotics.boards;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.lib.inventory.filter.AggregateFilter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.scheduler.ProcessorCandidateBoard;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotPlant;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.statements.ActionRobotFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Planter board. Fetches up to 32 matching seeds, then keeps planting from the held stack until it runs out. */
public class BoardRobotPlanter extends RedstoneBoardRobot implements ProcessorCandidateBoard {
    private static final int MAX_SEED_BATCH_SIZE = 32;

    private BlockIndex blockFound;

    private final IStackFilter seedFilter = stack -> !stack.isEmpty() && BuildCraftApi.service(BuildCraftServices.CROPS).isSeed(stack);

    public BoardRobotPlanter(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("planter").nbt();
    }

    @Override
    public String processorWorkCategory() { return getNBTHandler().getID(); }

    @Override
    public boolean processorSupportsQueue() { return true; }

    @Override
    public boolean processorScanCandidate(Level level, BlockPos pos) {
        ItemStack seed = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        // Plant compatibility is seed-dependent, including modded crop supports.
        // A seedless/charging planter must not populate a useless candidate cache.
        return seedFilter.matches(seed) && isPlantable(level, seed, pos);
    }

    @Override
    public boolean processorTargetEligible(Level level, BlockPos pos) {
        ItemStack seed = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        return seedFilter.matches(seed) && isPlantable(level, seed, pos);
    }

    @Override
    public boolean processorPlanningEligible(Level level, BlockPos pos) {
        return processorTargetEligible(level, pos);
    }

    @Override
    public void update() {
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && !seedFilter.matches(held)) {
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot,
                    new AggregateFilter(seedFilter, ActionRobotFilter.getGateFilter(robot.getLinkedStation())), MAX_SEED_BATCH_SIZE));
            return;
        }

        final ItemStack seed = held.copy();
        startDelegateAI(new AIRobotSearchAndGotoBlock(robot, false, (level, pos) -> isPlantable(level, seed, pos), 1));
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchAndGotoBlock search) {
            if (search.success()) {
                blockFound = search.getBlockFound();
                startDelegateAI(new AIRobotPlant(robot, blockFound));
            } else {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotPlant) {
            if (blockFound != null) {
                RobotProcessorWorkScheduler.executionFinished(robot, blockFound.toBlockPos(), ai.success());
            }
            releaseBlockFound();
        } else if (ai instanceof AIRobotFetchAndEquipItemStack) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        }
    }

    @Override
    public void end() {
        releaseBlockFound();
    }

    private Level robotLevel() {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
    }

    private boolean isPlantable(Level level, ItemStack seed, BlockPos pos) {
        if (!level.isLoaded(pos) || seed.isEmpty()) {
            return false;
        }
        if (level.isEmptyBlock(pos) || level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (robot.getRegistry() != null && robot.getRegistry().isTaken(new ResourceIdBlock(pos))
                && robot.getRegistry().robotIdTaking(new ResourceIdBlock(pos)) != robot.getRobotId()) {
            return false;
        }
        return BuildCraftApi.service(BuildCraftServices.CROPS).canSustainPlant(level, seed, pos);
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
}
