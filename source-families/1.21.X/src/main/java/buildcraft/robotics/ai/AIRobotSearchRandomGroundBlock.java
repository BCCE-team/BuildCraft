package buildcraft.robotics.ai;

import java.util.Random;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.area.IBox;
import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Searches a random X/Z column for the first solid ground block matching a filter.
 *
 * <p>This is the small BC7 helper used by the Bomber robot. Unlike the regular block search, it deliberately does not
 * find the nearest block; it picks random columns in the work zone/range so bombs are distributed across the area.</p>
 */
public class AIRobotSearchRandomGroundBlock extends AIRobot {
    private static final int MAX_ATTEMPTS = 4096;

    public BlockIndex blockFound;

    private int range;
    private IBlockFilter filter;
    private IZone zone;
    private int attempts;
    private final Random random = new Random();

    public AIRobotSearchRandomGroundBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchRandomGroundBlock(EntityRobotBase robot, int range, IBlockFilter filter, IZone zone) {
        this(robot);
        this.range = range;
        this.filter = filter;
        this.zone = zone;
    }

    @Override
    public void update() {
        if (filter == null) {
            setSuccess(false);
            terminate();
            return;
        }

        if (++attempts > MAX_ATTEMPTS) {
            setSuccess(false);
            terminate();
            return;
        }

        BlockPos column = nextColumn();
        if (column == null) {
            return;
        }

        Level level = robot.level();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        if (zone instanceof IBox box && box.min() != null && box.max() != null) {
            minY = Math.max(minY, box.min().getY());
            maxY = Math.min(maxY, box.max().getY());
        }
        if (minY > maxY) {
            return;
        }
        int x = column.getX();
        int z = column.getZ();

        for (int y = maxY; y >= minY; --y) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                continue;
            }

            if (filter.matches(level, pos)) {
                blockFound = new BlockIndex(pos);
                terminate();
                return;
            }

            if (!level.getBlockState(pos).isAir()) {
                return;
            }
        }
    }

    private BlockPos nextColumn() {
        if (zone != null) {
            return zone.getRandomBlockPos(random);
        }

        double radius = robot.level().getRandom().nextFloat() * Math.max(1, range);
        double angle = robot.level().getRandom().nextFloat() * Math.PI * 2.0D;
        int x = (int) Math.floor(Math.cos(angle) * radius + robot.getX());
        int z = (int) Math.floor(Math.sin(angle) * radius + robot.getZ());
        return new BlockPos(x, robot.getBlockY(), z);
    }

    @Override
    public boolean success() {
        return blockFound != null;
    }


    @Override
    public int getEnergyCost() {
        return 2;
    }
}
