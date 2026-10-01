package buildcraft.robotics.ai;

import java.util.function.Predicate;

import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Finds the closest matching reachable entity in the work zone using BuildCraft 7.1.x search semantics. */
public class AIRobotSearchEntity extends AIRobot {
    public Entity target;

    private float maxRange;
    private IZone zone;
    private Predicate<Entity> filter;

    public AIRobotSearchEntity(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchEntity(EntityRobotBase robot, Predicate<Entity> filter, float maxRange, IZone zone) {
        this(robot);
        this.filter = filter;
        this.maxRange = maxRange;
        this.zone = zone;
    }

    @Override
    public void start() {
        if (filter == null || maxRange <= 0.0F) {
            target = null;
            terminate();
            return;
        }

        double bestDistance = Double.MAX_VALUE;
        AABB box = robot.getBoundingBox().inflate(maxRange);

        for (Entity entity : robot.level().getEntities(robot, box, this::isValidTarget)) {
            double distance = robot.distanceToSqr(entity);
            if (distance < bestDistance && distance < maxRange * maxRange) {
                bestDistance = distance;
                target = entity;
            }
        }

        terminate();
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        if (robot.isKnownUnreachable(entity)) {
            return false;
        }
        if (zone != null && !zone.contains(new Vec3(entity.getX(), entity.getY(), entity.getZ()))) {
            return false;
        }
        return filter.test(entity);
    }

    @Override
    public boolean success() {
        return target != null;
    }

    @Override
    public int getEnergyCost() {
        return 2;
    }
}
