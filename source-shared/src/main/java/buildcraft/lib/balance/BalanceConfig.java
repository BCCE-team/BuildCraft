package buildcraft.lib.balance;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Each INI independently opts into its own balance values with [meta] use_config.
 * With use_config=false the built-in BCCE values remain authoritative.
 */
public final class BalanceConfig {
    private static volatile BalanceIni store;
    private static volatile boolean attempted;

    private BalanceConfig() {}

    public static void initialize() {
        if (attempted) return;
        synchronized (BalanceConfig.class) {
            if (attempted) return;
            BalanceIni ini = new BalanceIni(Path.of("config"));
            try {
                ini.load();
                store = ini;
                for (String warning : ini.warnings()) System.err.println("[BCCE/Balance] WARN " + warning);
                System.out.println("[BCCE/Balance] Machine INI: "
                    + (ini.useConfig(BalanceIni.FileKind.MACHINES) ? "enabled" : "built-in")
                    + "; robot INI: "
                    + (ini.useConfig(BalanceIni.FileKind.ROBOTS) ? "enabled" : "built-in"));
            } catch (IOException e) {
                // Never apply a partially parsed configuration.
                System.err.println("[BCCE/Balance] ERROR " + e.getMessage()
                    + ". Using built-in BCCE balance values; INI files were not overwritten.");
            } finally {
                attempted = true;
            }
        }
    }

    private static BalanceIni active(BalanceIni.FileKind kind) {
        initialize();
        BalanceIni ini = store;
        return ini != null && ini.useConfig(kind) ? ini : null;
    }

    public static double machine(String id, String key, double builtInValue) {
        BalanceIni ini = active(BalanceIni.FileKind.MACHINES);
        return ini == null ? builtInValue
            : ini.decimal(BalanceIni.FileKind.MACHINES, "machine." + id, key, builtInValue);
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
        BalanceIni ini = active(BalanceIni.FileKind.ROBOTS);
        return ini == null ? builtInValue
            : ini.decimal(BalanceIni.FileKind.ROBOTS, "robot." + boardKey(boardId), key, builtInValue);
    }
}
