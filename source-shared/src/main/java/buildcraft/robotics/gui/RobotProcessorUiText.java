package buildcraft.robotics.gui;

import buildcraft.robotics.RobotTaskNames;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.internal.mj.MjFormatting;
import buildcraft.robotics.container.ContainerRobotProcessor;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.network.chat.Component;

/** Shared, client-side text and contextual help for every Robot Control Panel GUI version. */
public final class RobotProcessorUiText {
    private static final String PREFIX = "gui.buildcraftrobotics.robot_processor.";
    private static final Set<String> STATUSES = Set.of(
            "Working", "Moving", "Charging", "Sleeping", "Docked", "Materials",
            "Available", "Occupied", "Offline", "Unknown");
    private static final Set<String> TASKS = Set.of(
            "idle", "offline", "unknown", "dock", "shut_down", "sleep", "charge",
            "unload", "load", "deliver", "harvest", "plant", "pump", "mine", "attack",
            "fetch", "build", "craft", "use_tool", "wait", "search", "move", "work");

    private RobotProcessorUiText() { }

    public static Component component(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    public static String text(String key, Object... args) {
        return component(key, args).getString();
    }

    // A narrow machine panel uses the short MJ unit even with long unit names enabled.
    private static Component energyRate(long microMjPerTick) {
        if (BCLibConfig.hidePowerValues) return Component.translatable("buildcraft.value.hidden");
        var gap = BCLibConfig.displayTimeGap;
        String key = gap == BCLibConfig.TimeGap.SECONDS
                ? "buildcraft.mj.flow.seconds.short" : "buildcraft.mj.flow.short";
        return Component.translatable(key, MjFormatting.formatMicroMj(gap.convertTicksToGap(microMjPerTick)));
    }

    static Component energyConsumption(ContainerRobotProcessor.PowerState state) {
        boolean starved = state.requested() > state.consumed();
        return component(starved ? "energy.need" : "energy.use",
                energyRate(starved ? state.requested() : state.consumed()));
    }

    static List<Component> energyTooltip(ContainerRobotProcessor menu) {
        var state = menu.getPowerState();
        Component amount = BCLibConfig.hidePowerValues ? Component.translatable("buildcraft.value.hidden")
                : Component.translatable("buildcraft.mj.static.short", MjFormatting.formatMicroMj(state.stored())
                        + " / " + MjFormatting.formatMicroMj(menu.getEnergyCapacity()));
        Component used = component("energy.use", energyRate(state.consumed()));
        if (state.requested() > state.consumed()) {
            return List.of(component("energy.title"), amount, used,
                    component("energy.need", energyRate(state.requested())));
        }
        return List.of(component("energy.title"), amount, used);
    }

    public static List<Component> help(String key) {
        return List.of(component("help." + key + ".title"), component("help." + key + ".desc"));
    }

    public static List<Component> zoneHelp(String key) {
        return List.of(Component.translatable("buildcraftrobotics.help.zone_planner." + key + ".title"),
                Component.translatable("buildcraftrobotics.help.zone_planner." + key + ".desc"));
    }

    public static String status(String raw) {
        String value = raw == null ? "Unknown" : raw;
        return STATUSES.contains(value) ? text("status." + value.toLowerCase(Locale.ROOT)) : value;
    }

    public static String task(String raw) {
        String name = RobotTaskNames.shortName(raw);
        String token = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        return TASKS.contains(token) ? text("task." + token) : name;
    }

    public static String direction(int side) {
        String[] keys = {"down", "up", "north", "south", "west", "east"};
        return side < 0 || side >= keys.length ? "?" : text("direction." + keys[side]);
    }

    public static String board(String raw) {
        if (raw == null || raw.isBlank() || "Unknown".equals(raw)) return text("board.unknown");
        String value = raw;
        int colon = value.lastIndexOf(':');
        if (colon >= 0) value = value.substring(colon + 1);
        if (value.startsWith("boardRobot") || value.startsWith("BoardRobot"))
            value = value.substring("boardRobot".length());
        if (value.startsWith("robot_")) value = value.substring("robot_".length());
        if (value.isBlank()) return text("board.unknown");
        String token = value.toLowerCase(Locale.ROOT).replace("_", "");
        // Different Minecraft versions report board IDs with different casing.
        if ("leafcutter".equals(token)) token = "leavecutter";
        if ("fluidcarrier".equals(token)) token = "fluidcarrier";
        String key = PREFIX + "board." + token;
        String translated = Component.translatable(key).getString();
        return translated.equals(key) ? value : translated;
    }
}
