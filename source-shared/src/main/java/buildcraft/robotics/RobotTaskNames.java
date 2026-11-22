package buildcraft.robotics;

import java.util.Locale;

/** Short, user-facing names for the current robot action (not its redstone-board class). */
public final class RobotTaskNames {
    private RobotTaskNames() { }

    /** Never expose internal AI class names or truncated class-name fragments in the GUI. */
    public static String shortName(String raw) {
        if (raw == null || raw.isBlank()) return "Idle";
        // The menu receives the leaf AI class name; older snapshots may also
        // contain the already-spaced class name sent by previous versions.
        String key = raw.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
        if (key.equals("idle") || key.equals("none")) return "Idle";
        if (key.equals("offline")) return "Offline";
        if (key.equals("unknown")) return "Unknown";
        // A board is a profession/work controller, not an action being performed.
        // Its child AIRobot is visible once a real action begins.
        if (key.startsWith("boardrobot") || key.startsWith("redstoneboard")) return "Idle";
        if (key.startsWith("airobot")) key = key.substring("airobot".length());
        if (key.contains("linktodock") || key.contains("gotodock") || key.contains("docking")
                || key.contains("todock")) return "Dock";
        if (key.contains("shutdown")) return "Shut Down";
        if (key.contains("sleep")) return "Sleep";
        if (key.contains("recharge") || key.contains("charge")) return "Charge";
        if (key.contains("unload")) return "Unload";
        if (key.contains("load")) return "Load";
        if (key.contains("deliver")) return "Deliver";
        if (key.contains("shear")) return "Shear";
        if (key.contains("harvest")) return "Harvest";
        if (key.contains("plant")) return "Plant";
        if (key.contains("pump")) return "Pump";
        if (key.contains("break") || key.contains("mine") || key.contains("dig")) return "Mine";
        if (key.contains("attack") || key.contains("fight") || key.contains("kill")) return "Attack";
        if (key.contains("fetch") || key.contains("equip") || key.contains("pickup")) return "Fetch";
        if (key.contains("build") || key.contains("place")) return "Build";
        if (key.contains("craft") || key.contains("assemble")) return "Craft";
        if (key.contains("stripes") || key.contains("usetool") || key.contains("useitem")) return "Use Tool";
        if (key.contains("wait")) return "Wait";
        if (key.contains("search") || key.contains("scan")) return "Search";
        if (key.contains("move") || key.contains("path") || key.contains("goto")
                || key.contains("travel") || key.contains("straight")) return "Move";
        // Unknown add-on actions remain readable, not "Board Robot Mi...".
        return "Work";
    }
}
