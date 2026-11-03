package buildcraft.gametest;

import java.util.Map;
import java.util.TreeMap;
import buildcraft.lib.internal.debug.BCLog;

/** Stable semantic observations from completed, world-backed GameTests.
 *  Only emit after all scenario assertions pass. Target-specific Minecraft
 *  versions, tick durations and internal serialization bytes are deliberately
 *  excluded from this wire format.
 */
public final class SystemTrace {
    private SystemTrace() {}

    public static void emit(String scenario, String... fields) {
        if (!scenario.matches("[a-z][a-z0-9_]*") || fields.length == 0 || (fields.length & 1) != 0) {
            throw new IllegalArgumentException("Invalid system trace dimensions");
        }
        Map<String, String> values = new TreeMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            String key = fields[i];
            String value = fields[i + 1];
            if (!key.matches("[a-z][a-zA-Z0-9_]*") || value == null
                || !value.matches("[a-zA-Z0-9_.:-]+") || values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Invalid or duplicate system trace field: " + key);
            }
        }
        StringBuilder output = new StringBuilder("BCCE_SYSTEM_TRACE|").append(scenario);
        for (Map.Entry<String, String> field : values.entrySet()) {
            output.append('|').append(field.getKey()).append('=').append(field.getValue());
        }
        BCLog.logger.info("{}", output);
    }
}
