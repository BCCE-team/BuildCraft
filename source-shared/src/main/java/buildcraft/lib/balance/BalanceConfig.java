package buildcraft.lib.balance;

import java.io.IOException;
import java.util.List;
import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import buildcraft.lib.platform.config.ConfigBinding;
import java.nio.file.Path;

/**
 * Existing INI files are migration inputs. The BCCE registry owns effective balance values.
 */
public final class BalanceConfig {
    private static volatile boolean attempted;

    private BalanceConfig() {}

    public static void initialize() {
        if (attempted) return;
        synchronized (BalanceConfig.class) {
            if (attempted) return;
            BalanceIni ini = new BalanceIni(Path.of("config"));
            if (!java.nio.file.Files.exists(Path.of("config", "buildcraft-machines.ini")) && !java.nio.file.Files.exists(Path.of("config", "buildcraft-robots.ini"))) ini = null;
            try {
                if (ini != null) {
                    ini.load();
                    for (String warning : ini.warnings()) System.err.println("[BCCE/Balance] WARN " + warning);
                    System.out.println("[BCCE/Balance] Machine INI: "
                        + (ini.useConfig(BalanceIni.FileKind.MACHINES) ? "enabled" : "built-in")
                        + "; robot INI: "
                        + (ini.useConfig(BalanceIni.FileKind.ROBOTS) ? "enabled" : "built-in"));
                }
            } catch (IOException e) {
                // Never apply a partially parsed configuration.
                System.err.println("[BCCE/Balance] ERROR " + e.getMessage()
                    + ". Using built-in BCCE balance values; INI files were not overwritten.");
                ini = null;
            }
            for (BalanceIni.Parameter p : BalanceIni.runtimeParameters()) {
                String id = id(p.section(), p.key());
                String object = p.section().substring(p.section().indexOf('.') + 1);
                String name = ConfigBinding.human(object.substring(object.lastIndexOf(':') + 1));
                List<String> category = List.of(p.file() == BalanceIni.FileKind.MACHINES ? "Machines" : "Robots", name);
                String label = label(p);
                String description = description(p);
                String key = "config.buildcraft.balance." + p.section().replace(':', '.') + "." + p.key();
                String migrated = null;
                if (ini != null && ini.useConfig(p.file())) {
                    double oldValue = ini.decimal(p.file(), p.section(), p.key(), Double.parseDouble(p.defaultValue()));
                    migrated = p.integral() ? Long.toString((long) oldValue) : Double.toString(oldValue);
                }
                ConfigurationRegistry.INSTANCE.register(new ConfigDefinition(id, Section.BALANCE, category, Scope.SERVER,
                    p.integral() ? Kind.INTEGER : Kind.DECIMAL, p.defaultValue(), p.minimum(), p.maximum(), List.of(),
                    key + ".name", key + ".description", key + ".tooltip", label, description,
                    tooltip(p),
                    ApplyPolicy.WORLD_RESTART), migrated);
            }
            attempted = true;
        }
    }

    private static String id(String section, String key) {
        return "buildcraft:balance/" + section.replace('.', '/').replace(':', '/') + "/" + key;
    }

    private static String label(BalanceIni.Parameter p) {
        return switch (p.key()) {
            case "energy_cost_multiplier" -> "Energy Cost Multiplier";
            case "energy_multiplier" -> "Block Breaking Energy Multiplier";
            case "work_speed_multiplier" -> "Work Speed Multiplier";
            case "movement_speed_multiplier" -> "Movement Speed Multiplier";
            case "energy_per_bucket_mj" -> "Base Pumping Energy Cost";
            case "tank_capacity_mb" -> "Internal Tank Capacity";
            case "max_distance_blocks" -> p.section().equals("machine.markers") ? "Marker Connection Range" : "Fluid Scan Range";
            case "consume_water_sources" -> "Consume Water Sources";
            case "frame_min_height" -> "Minimum Frame Height";
            case "max_tasks_per_tick" -> "Base Task Rate";
            case "power_divisor" -> "Extra Task Energy Divisor";
            case "max_frame_speed" -> "Drill Movement Speed Limit";
            case "max_mine_rate" -> "Block Mining Rate Limit";
            case "max_depth" -> "Maximum Mining Depth";
            default -> ConfigBinding.human(p.key());
        };
    }

    private static String description(BalanceIni.Parameter p) {
        return switch (p.key()) {
            case "energy_cost_multiplier" -> p.file() == BalanceIni.FileKind.ROBOTS ? "Scale energy spent by robot AI actions." : "Scale the energy required by this machine's operations.";
            case "energy_multiplier" -> "Scale base block-breaking energy costs.";
            case "work_speed_multiplier" -> "Scale quarry task and configured speed limits.";
            case "movement_speed_multiplier" -> "Scale robot travel speed.";
            case "energy_per_bucket_mj" -> "Set the pump's base energy cost per displayed volume.";
            case "tank_capacity_mb" -> "Set the maximum liquid stored inside the pump.";
            case "max_distance_blocks" -> p.section().equals("machine.markers") ? "Set how far aligned markers can connect." : "Set how far the pump searches connected liquid.";
            case "consume_water_sources" -> "0: preserve renewable water. 1: drain water normally.";
            case "frame_min_height" -> "Set the minimum height of a newly planned quarry frame.";
            case "max_tasks_per_tick" -> "Set the quarry's base task rate at full power.";
            case "power_divisor" -> "Control the extra energy penalty for successive tasks.";
            case "max_frame_speed" -> "Limit drill movement speed; 0 disables this cap.";
            case "max_mine_rate" -> "Limit block mining speed; 0 disables this cap.";
            case "max_depth" -> "Limit the vertical reach of mining machines.";
            default -> ConfigBinding.human(p.key());
        };
    }

    private static String tooltip(BalanceIni.Parameter p) {
        return switch (p.key()) {
            case "energy_cost_multiplier" -> p.file() == BalanceIni.FileKind.ROBOTS
                ? "Scales energy charged by the robot AI during work and travel.\n\n1 = normal cost; 2 = twice the cost; 0.5 = half. Does not change battery capacity or the laser recipe cost of the board."
                : "Scales this machine's applicable operation costs after base/API values.\n\n1 = normal cost; 2 = twice the cost; 0.5 = half. Available power can limit throughput. Mining costs may also include the global block breaking multiplier.";
            case "energy_multiplier" -> "Scales the shared block-breaking energy calculation used by mining machines. Applies before machine-specific cost modifiers.\n\n1 = normal cost; 2 = twice the base cost. The minimum is 1; this setting cannot make mining cheaper than the base calculation.";
            case "work_speed_multiplier" -> "Scales the quarry's base tasks per tick and positive drill/mining rate limits.\n\n1 = normal; 2 = twice the base limits. Actual throughput depends on power, tasks, and energy costs. The task loop always permits at least one attempt.";
            case "movement_speed_multiplier" -> "Scales the robot's configured travel speed and corresponding route energy estimates.\n\n1 = normal speed; 2 = twice the base speed; 0.5 = half. Does not directly speed up every work action.";
            case "energy_per_bucket_mj" -> "Base MJ cost of a pump extraction cycle (normally one bucket), before its energy cost multiplier. The editor converts the displayed unit; stored costs use MJ per bucket.";
            case "tank_capacity_mb" -> "Maximum liquid volume stored inside the pump. When reducing capacity, a loaded pump preserves excess liquid until it can fit into the new capacity.";
            case "max_distance_blocks" -> p.section().equals("machine.markers")
                ? "Maximum connection distance between aligned markers along an axis. Existing structures are not resized by this setting."
                : "Maximum distance from the target fluid position when scanning connected liquid. Unloaded chunks are not loaded to extend the scan.";
            case "consume_water_sources" -> "0: Preserve recognized renewable water sources while pumping.\n1: Drain water normally, including renewable sources.\n\nOther liquids are drained normally in either mode.";
            case "frame_min_height" -> "Minimum height of a newly planned quarry frame, measured in blocks. Does not resize frames already built.";
            case "max_tasks_per_tick" -> "Base task-processing limit at full power. Actual tasks per tick also depend on available energy and the work speed multiplier; the loop permits at least one attempt. Display units do not change tick scheduling.";
            case "power_divisor" -> "Controls extra energy charged for successive quarry tasks within the same tick.\n\n0: No extra-task energy penalty.\nHigher positive values reduce the additional penalty; smaller positive values increase it.";
            case "max_frame_speed" -> "Limits the quarry drill's travel distance per unit of time. A positive limit is scaled by the work speed multiplier.\n\n0: No explicit movement-rate cap. Available power and other limits still apply.";
            case "max_mine_rate" -> "Limits block-breaking progress per unit of time. A positive limit is scaled by the work speed multiplier.\n\n0: No explicit mining-rate cap. Available power and other limits still apply.";
            case "max_depth" -> "Maximum vertical mining depth below the starting area or machine, in blocks. The world's build-height limits still apply.";
            default -> description(p);
        };
    }

    public static double machine(String id, String key, double builtInValue) {
        initialize();
        return ConfigurationRegistry.INSTANCE.value(id("machine." + id, key)).map(Double::parseDouble).orElse(builtInValue);
    }

    public static int machineInt(String id, String key, int builtInValue) {
        return (int) machine(id, key, builtInValue);
    }

    /** Convert original BCCE board ids without a per-tick registry lookup. */
    public static String boardKey(String legacyId) {
        if (legacyId == null) return "empty";
        int colon = legacyId.lastIndexOf(':');
        // Do not accidentally apply a built-in robot's balance to another addon's board.
        if (colon >= 0 && !legacyId.substring(0, colon).equals("buildcraft")) return legacyId;
        String raw = legacyId.substring(colon + 1);
        if (raw.startsWith("boardRobot")) raw = raw.substring("boardRobot".length());
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(raw.charAt(i - 1))) key.append('_');
            key.append(Character.toLowerCase(c));
        }
        return key.toString();
    }

    public static double robot(String boardId, String key, double builtInValue) {
        initialize();
        return ConfigurationRegistry.INSTANCE.value(id("robot." + boardKey(boardId), key)).map(Double::parseDouble).orElse(builtInValue);
    }
}
