/** Copyright (c) 2011-2015 SpaceToad and the BuildCraft team
 * SPDX-License-Identifier: MPL-2.0 */
package buildcraft.lib.internal.debug;

import java.util.Locale;

/** Per-subsystem debug switches, gated by the global log profile. */
public final class BCDebugging {
    public enum DebugStatus { NONE, ENABLE, LOGGING_ONLY, ALL }
    private enum DebugLevel { LOG, COMPLEX }

    private BCDebugging() { }

    private static DebugStatus resolveStatus() {
        // Legacy JVM switches remain supported; new installations should use buildcraft.log.profile.
        String legacy = System.getProperty("buildcraft.debug", "").toLowerCase(Locale.ROOT);
        switch (legacy) {
            case "disable": return DebugStatus.NONE;
            case "all": return DebugStatus.ALL;
            case "log": return DebugStatus.LOGGING_ONLY;
            case "enable": return DebugStatus.ENABLE;
            default: break;
        }
        return switch (BCLogProfile.current()) {
            case DEFAULT -> DebugStatus.NONE;
            case DEBUG -> DebugStatus.ENABLE;
            case HARD_DEBUGGING -> DebugStatus.LOGGING_ONLY;
            case VERBOSE -> DebugStatus.ALL;
        };
    }

    public static boolean shouldDebugComplex(String option) {
        return shouldDebug(option, DebugLevel.COMPLEX);
    }

    public static boolean shouldDebugLog(String option) {
        return shouldDebug(option, DebugLevel.LOG);
    }

    private static boolean shouldDebug(String option, DebugLevel type) {
        String actual = System.getProperty("buildcraft." + option + ".debug", "")
            .trim().toLowerCase(Locale.ROOT);
        if ("false".equals(actual)) return false;
        if ("true".equals(actual) || "complex".equals(actual)
            || type.name().toLowerCase(Locale.ROOT).equals(actual)) return true;
        // Core config is loaded after lib bootstrap; resolve dynamically so changing
        // Core > Debug actually enables existing subsystem diagnostics as well.
        DebugStatus status = resolveStatus();
        if (type == DebugLevel.COMPLEX) return status == DebugStatus.ALL;
        return status == DebugStatus.LOGGING_ONLY || status == DebugStatus.ALL;
    }
}
