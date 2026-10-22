package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.inventory.filter.ArrayStackOrListFilter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGotoBlock;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoad;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotSearchRandomGroundBlock;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.lib.internal.area.IZone;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/** Classic BuildCraft Bomber robot: loads TNT, flies over random ground in its work zone, and drops primed TNT. */
public class BoardRobotBomber extends RedstoneBoardRobot {
    private static final IStackFilter TNT_FILTER = new ArrayStackOrListFilter(new ItemStack(Items.TNT));
    private static final int SEARCH_RANGE = 100;
    private static final int FLYING_HEIGHT = 20;
    private static final int TNT_FUSE = 37;
    private static final int BLAST_SAFETY_RADIUS = 6;

    private BlockPos bombTarget;

    public BoardRobotBomber(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("bomber").nbt();
    }

    @Override
    public void update() {
        if (!hasTnt()) {
            startDelegateAI(new AIRobotGotoStationAndLoad(robot, TNT_FILTER, 1, true));
            return;
        }

        startDelegateAI(new AIRobotSearchRandomGroundBlock(robot, SEARCH_RANGE, this::isValidBombTarget, robot.getZoneToWork()));
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationAndLoad) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotSearchRandomGroundBlock search) {
            if (search.success()) {
                BlockPos target = search.blockFound.toBlockPos();
                if (canBombTarget(target, OperationMode.SIMULATE)) {
                    bombTarget = target;
                    startDelegateAI(new AIRobotGotoBlock(robot, target.getX(), target.getY() + FLYING_HEIGHT, target.getZ()));
                } else {
                    bombTarget = null;
                    startDelegateAI(new AIRobotGotoSleep(robot));
                }
            } else {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoBlock) {
            if (ai.success() && bombTarget != null && canBombTarget(bombTarget, OperationMode.EXECUTE)) {
                dropTnt(bombTarget);
            }
            bombTarget = null;
            startDelegateAI(new AIRobotGotoSleep(robot));
        } else if (ai instanceof AIRobotGotoSleep) {
            terminate();
        }
    }

    private boolean isValidBombTarget(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (pos.getY() >= level.getMaxBuildHeight() - FLYING_HEIGHT) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        return !state.isAir();
    }

    private boolean hasTnt() {
        for (int slot = 0; slot < robot.getContainerSize(); slot++) {
            ItemStack stack = robot.getItem(slot);
            if (!stack.isEmpty() && TNT_FILTER.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeOneTnt() {
        for (int slot = 0; slot < robot.getContainerSize(); slot++) {
            ItemStack stack = robot.getItem(slot);
            if (!stack.isEmpty() && TNT_FILTER.matches(stack)) {
                robot.removeItem(slot, 1);
                robot.setChanged();
                return true;
            }
        }
        return false;
    }

    private boolean canBombTarget(BlockPos target, OperationMode mode) {
        IZone zone = robot.getZoneToWork();
        // Vanilla TNT has blast strength 4; use a conservative six-block sphere so a board never deliberately
        // primes TNT close enough to mutate blocks outside its configured work zone.
        int radiusSq = BLAST_SAFETY_RADIUS * BLAST_SAFETY_RADIUS;
        for (int dx = -BLAST_SAFETY_RADIUS; dx <= BLAST_SAFETY_RADIUS; dx++) {
            for (int dy = -BLAST_SAFETY_RADIUS; dy <= BLAST_SAFETY_RADIUS; dy++) {
                for (int dz = -BLAST_SAFETY_RADIUS; dz <= BLAST_SAFETY_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    BlockPos affected = target.offset(dx, dy, dz);
                    if (zone != null && !zone.contains(affected)) {
                        return false;
                    }
                    if (!robot.getCommandSenderWorld().isLoaded(affected)) {
                        return false;
                    }
                    if (!robot.getCommandSenderWorld().getBlockState(affected).isAir()
                            && !RobotAutomationSupport.permitsBlock(
                                robot, robot.getCommandSenderWorld(), affected,
                                WorldOperationKind.BLOCK_BREAK, mode
                            )) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void dropTnt(BlockPos target) {
        if (robot.level().isClientSide || !removeOneTnt()) {
            return;
        }

        PrimedTnt tnt = new PrimedTnt(robot.level(), target.getX() + 0.5D, robot.getY() - 1.0D,
                target.getZ() + 0.5D, robot);
        tnt.setFuse(TNT_FUSE);
        robot.level().addFreshEntity(tnt);
        robot.level().playSound(null, tnt.getX(), tnt.getY(), tnt.getZ(), SoundEvents.TNT_PRIMED,
                SoundSource.BLOCKS, 1.0F, 1.0F);
    }
}
