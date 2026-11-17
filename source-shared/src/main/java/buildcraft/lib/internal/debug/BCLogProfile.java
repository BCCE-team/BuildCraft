/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * SPDX-License-Identifier: MPL-2.0 */
package buildcraft.lib.internal.debug;

import java.util.Locale;
import java.util.Objects;

/**
 * Logging policy shared by all loaders. The ordinary profile comes from
 * BuildCraft Core's [debug] loggingProfile config value. Explicit launch
 * overrides still have priority, including on dedicated servers.
 */
public final class BCLogProfile {
    public enum Profile {
        DEFAULT, DEBUG, HARD_DEBUGGING, VERBOSE;

        public boolean atLeast(Profile required) {
            return ordinal() >= required.ordinal();
        }
    }

    public static final String PROPERTY = "buildcraft.log.profile";
    public static final String ENVIRONMENT = "BUILDCRAFT_LOG_PROFILE";
    private static final Selection OVERRIDE = selectOverride();
    private static volatile Profile coreProfile = Profile.DEFAULT;

    private BCLogProfile() { }

    /** Updated when Core COMMON config loads or reloads. */
    public static void applyCoreProfile(Profile profile) {
        coreProfile = Objects.requireNonNull(profile, "profile");
    }

    public static Profile current() {
        return OVERRIDE.overridden ? OVERRIDE.profile : coreProfile;
    }

    public static boolean atLeast(Profile minimum) {
        return current().atLeast(minimum);
    }

    public static String configurationWarning() {
        return OVERRIDE.warning;
    }

    public static boolean hasExternalOverride() {
        return OVERRIDE.overridden;
    }

    public static Profile parse(String value) {
        if (value == null) return null;
        String normal = value.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return switch (normal) {
            case "default" -> Profile.DEFAULT;
            case "debug" -> Profile.DEBUG;
            case "hard", "hard-debug", "hard-debugging", "harddebugging" -> Profile.HARD_DEBUGGING;
            case "verbose" -> Profile.VERBOSE;
            default -> null;
        };
    }

    private static Selection choose(String value, String source) {
        Profile parsed = parse(value);
        if (parsed != null) return new Selection(parsed, true, null);
        return new Selection(Profile.DEFAULT, true, "Invalid BuildCraft logging profile '" + value
            + "' from " + source + "; using default. Valid values: default, debug, hard-debugging, verbose");
    }

    private static Selection selectOverride() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) return choose(property, "-D" + PROPERTY);
        String legacy = System.getProperty("buildcraft.debug", "").trim().toLowerCase(Locale.ROOT);
        switch (legacy) {
            case "all": return new Selection(Profile.VERBOSE, true, null);
            case "log": return new Selection(Profile.HARD_DEBUGGING, true, null);
            case "enable": return new Selection(Profile.DEBUG, true, null);
            case "disable": return new Selection(Profile.DEFAULT, true, null);
            default: break;
        }
        String environment = System.getenv(ENVIRONMENT);
        if (environment != null && !environment.isBlank()) return choose(environment, ENVIRONMENT);
        return new Selection(Profile.DEFAULT, false, null);
    }

    private record Selection(Profile profile, boolean overridden, String warning) { }
}
