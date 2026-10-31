package buildcraft.robotics;

import java.util.Locale;

import buildcraft.core.BCCoreConfig;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side, opt-in trace for diagnosing robot state transitions and docking races.
 * Logging is deliberately very verbose when enabled. No message formatting or
 * station inspection takes place while the common config flag is disabled.
 */
public final class RobotTrace {
    private RobotTrace() {
    }

    public static boolean enabled() {
        return BCCoreConfig.verboseRobotLogging;
    }

    public static String name(AIRobot ai) {
        return ai == null ? "none" : ai.getClass().getSimpleName();
    }

    public static void cycle(AIRobot ai) {
        if (!enabled() || ai == null) return;
        event(ai.robot, "AI_TICK", "ai=%s child=%s cost=%d success=%s",
                name(ai), name(ai.getDelegateAI()), ai.getEnergyCost(), ai.success());
    }

    public static void event(EntityRobotBase robot, String event, String format, Object... arguments) {
        if (!enabled()) return;
        String detail;
        try {
            detail = String.format(Locale.ROOT, format, arguments);
        } catch (RuntimeException badFormat) {
            detail = format + " [trace-format-error=" + badFormat.getClass().getSimpleName() + "]";
        }
        if (robot == null) {
            BCLog.logger.info("[BCCE/RobotTrace] event={} {}", event, detail);
            return;
        }
        Vec3 velocity = robot.getDeltaMovement();
        DockingStation home = robot.getLinkedStation();
        DockingStation dock = robot.getDockingStation();
        //? if <1.20 {
        long tick = robot.level.getGameTime();
        //?} else {
        /*?
        long tick = robot.level().getGameTime();
        ?*/
        //?}
        BCLog.logger.info(
                "[BCCE/RobotTrace] tick={} id={} pos=({},{},{}) vel=({},{},{}) energy={} "
                        + "home={} dock={} event={} {}",
                tick, robot.getRobotId(),
                robot.getX(), robot.getY(), robot.getZ(),
                velocity.x, velocity.y, velocity.z, robot.getEnergy(),
                home == null ? "none" : home.toString(),
                dock == null ? "none" : dock.toString(), event, detail);
    }
}
