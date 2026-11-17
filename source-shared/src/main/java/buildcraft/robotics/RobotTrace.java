package buildcraft.robotics;

import java.util.Locale;

import buildcraft.core.BCCoreConfig;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.debug.BCLogProfile;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side, opt-in trace for diagnosing robot state transitions and docking races.
 * Hard-debugging (including the general VERBOSE profile) emits meaningful transitions.
 * Only the independent verboseRobotLogging toggle enables repeated waits and per-tick traces.
 */
public final class RobotTrace {
    private RobotTrace() {
    }

    public static boolean enabled() {
        return BCCoreConfig.verboseRobotLogging
            || BCLogProfile.atLeast(BCLogProfile.Profile.HARD_DEBUGGING);
    }

    public static String name(AIRobot ai) {
        return ai == null ? "none" : ai.getClass().getSimpleName();
    }

    public static void cycle(AIRobot ai) {
        if (ai == null || !BCCoreConfig.verboseRobotLogging) return;
        event(ai.robot, "AI_TICK", "ai=%s child=%s cost=%d success=%s",
                name(ai), name(ai.getDelegateAI()), ai.getEnergyCost(), ai.success());
    }

    public static void event(EntityRobotBase robot, String event, String format, Object... arguments) {
        if (!enabled()) return;
        // Polling, repeated waits and motion frames are not useful as hard-debugging events.
        if (!BCCoreConfig.verboseRobotLogging
            && ("STRAIGHT_PROGRESS".equals(event) || "GATE_COMMAND_UNCHANGED".equals(event)
                || "STATION_WAIT".equals(event) || "STATION_RESTORE_WAIT".equals(event)
                || "NEAREST_WORK_NO_TARGET".equals(event) || "DOCK_RETURN_HOLD".equals(event))) return;
        String detail;
        try {
            detail = String.format(Locale.ROOT, format, arguments);
        } catch (RuntimeException badFormat) {
            detail = format + " [trace-format-error=" + badFormat.getClass().getSimpleName() + "]";
        }
        if (robot == null) {
            emit("[BCCE/RobotTrace] event={} {}", event, detail);
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
        emit(
                "[BCCE/RobotTrace] tick={} id={} pos=({},{},{}) vel=({},{},{}) energy={} "
                        + "home={} dock={} event={} {}",
                tick, robot.getRobotId(),
                robot.getX(), robot.getY(), robot.getZ(),
                velocity.x, velocity.y, velocity.z, robot.getEnergy(),
                home == null ? "none" : home.toString(),
                dock == null ? "none" : dock.toString(), event, detail);
    }
    private static void emit(String message, Object... arguments) {
        if (BCCoreConfig.verboseRobotLogging) BCLog.explicitEvent(message, arguments);
        else BCLog.event(message, arguments);
    }

}
