package buildcraft.robotics.internal.legacy.robots;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the velocity used for robot AI navigation independent of external impulses.
 *
 * <p>Vanilla fishing rods can directly change an entity's velocity when reeled in,
 * bypassing {@code isPushable()} and {@code push(Entity)}. A BuildCraft robot has
 * no vanilla friction while flying, so this foreign velocity may otherwise keep
 * it flying through blocks long after its AI has stopped moving it.
 *
 * <p>Only the server owns the authoritative movement. A robot snapshots its
 * movement at the end of its tick and restores it at the beginning of its next
 * tick, before the AI runs. AI commands made during the robot tick remain intact.
 * No position, collision, or pathfinding logic is modified.
 */
public final class RobotMotionGuard {
    private Vec3 lastControlledVelocity = Vec3.ZERO;
    private boolean hasSnapshot;

    /**
     * Discard motion added since the last robot tick, including fishing pulls.
     * On the first tick start with no velocity; restored robots may otherwise
     * inherit a stale fishing impulse saved before this fix was installed.
     * Their AI can set its normal movement immediately afterward.
     */
    public void beforeRobotTick(Entity robot) {
        Vec3 expected = hasSnapshot ? lastControlledVelocity : Vec3.ZERO;
        if (!expected.equals(robot.getDeltaMovement())) {
            robot.setDeltaMovement(expected);
        }
    }

    /** Remember the post-AI, post-physics velocity for the following tick. */
    public void afterRobotTick(Entity robot) {
        lastControlledVelocity = robot.getDeltaMovement();
        hasSnapshot = true;
    }
}
