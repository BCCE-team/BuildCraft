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
            for (BalanceIni.Parameter p : BalanceIni.parameters()) {
                String id = id(p.section(), p.key());
                String object = p.section().substring(p.section().indexOf('.') + 1);
                String name = ConfigBinding.human(object.substring(object.lastIndexOf(':') + 1));
                List<String> category = List.of(p.file() == BalanceIni.FileKind.MACHINES ? "Machines" : "Robots", name);
                String label = ConfigBinding.human(p.key());
                String description = description(p.key());
                String key = "config.buildcraft.balance." + p.section().replace(':', '.') + "." + p.key();
                String migrated = null;
                if (ini != null && ini.useConfig(p.file())) {
                    double oldValue = ini.decimal(p.file(), p.section(), p.key(), Double.parseDouble(p.defaultValue()));
                    migrated = p.integral() ? Long.toString((long) oldValue) : Double.toString(oldValue);
                }
                ConfigurationRegistry.INSTANCE.register(new ConfigDefinition(id, Section.BALANCE, category, Scope.SERVER,
                    p.integral() ? Kind.INTEGER : Kind.DECIMAL, p.defaultValue(), p.minimum(), p.maximum(), List.of(),
                    key + ".name", key + ".description", key + ".tooltip", label, description,
                    description + (p.key().equals("max_frame_speed") || p.key().equals("max_mine_rate") ? " Zero uses the built-in automatic limit." : ""),
                    ApplyPolicy.WORLD_RESTART), migrated);
            }
            attempted = true;
        }
    }

    private static String id(String section, String key) {
        return "buildcraft:balance/" + section.replace('.', '/').replace(':', '/') + "/" + key;
    }

    private static String description(String key) {
        return switch (key) {
            case "energy_cost_multiplier", "energy_multiplier" -> "Multiplier for the energy required by an operation.";
            case "work_speed_multiplier" -> "Multiplier for the machine's work rate.";
            case "movement_speed_multiplier" -> "Multiplier for robot movement speed.";
            case "energy_per_bucket_mj" -> "Energy required to pump one bucket (MJ).";
            case "tank_capacity_mb" -> "Capacity of the pump's internal tank (mB).";
            case "max_distance_blocks" -> "Maximum working distance in blocks.";
            case "consume_water_sources" -> "Whether the pump consumes water source blocks (0 = no, 1 = yes).";
            case "frame_min_height" -> "Minimum quarry frame height in blocks.";
            case "max_tasks_per_tick" -> "Maximum number of quarry tasks processed per tick.";
            case "power_divisor" -> "Divisor used in the quarry task power allocation.";
            case "max_frame_speed" -> "Maximum quarry frame movement speed.";
            case "max_mine_rate" -> "Maximum quarry block mining rate.";
            case "max_depth" -> "Maximum mining depth in blocks.";
            default -> ConfigBinding.human(key);
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
