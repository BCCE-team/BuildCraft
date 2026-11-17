/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * SPDX-License-Identifier: MPL-2.0 */
package buildcraft.lib.internal.debug;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;

/**
 * Applies the BCCE profile only to the BuildCraft logger. Explicitly overriding the
 * ExtendedLogger enable checks prevents old direct logger.info/warn calls from evading
 * the profile. Error and warning events remain enabled in every profile.
 */
final class BCProfileLogger extends ExtendedLoggerWrapper {
    private static final long serialVersionUID = 1L;

    private BCProfileLogger(ExtendedLogger delegate) {
        super(delegate, "BuildCraft", delegate.getMessageFactory());
    }

    static Logger create() {
        Logger base = LogManager.getLogger("BuildCraft");
        if (base instanceof ExtendedLogger extended) {
            // Log4j Core normally checks its configured level a second time at the backend.
            // Core config loads after the logger is created. Keep the *BuildCraft* backend
            // at TRACE and let this wrapper enforce the selected runtime policy, otherwise
            // enabling DEBUG in Core would silently drop those messages in Log4j Core.
            // Reflection avoids a compile-time dependency on log4j-core in Forge/Fabric.
            try {
                base.getClass().getMethod("setLevel", Level.class).invoke(base, Level.TRACE);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // Providers without a mutable logger still use their native thresholds.
            }
            return new BCProfileLogger(extended);
        }
        // Extremely unusual Log4j providers: preserve the logger rather than breaking game startup.
        return base;
    }

    private static boolean allowed(Level level, Marker marker) {
        if (level == Level.OFF) return false;
        if (level.isMoreSpecificThan(Level.WARN)) return true;
        BCLogProfile.Profile profile = BCLogProfile.current();
        if (level == Level.INFO) {
            return profile.atLeast(BCLogProfile.Profile.HARD_DEBUGGING)
                || (profile.atLeast(BCLogProfile.Profile.DEBUG)
                    && marker != null && marker.isInstanceOf(BCLog.DIAGNOSTIC_MARKER))
                || (marker != null && marker.isInstanceOf(BCLog.EXPLICIT_MARKER));
        }
        if (level == Level.DEBUG) return profile.atLeast(BCLogProfile.Profile.DEBUG);
        return profile == BCLogProfile.Profile.VERBOSE;
    }

    @Override
    public Level getLevel() {
        return switch (BCLogProfile.current()) {
            case DEFAULT -> Level.WARN;
            case DEBUG, HARD_DEBUGGING -> Level.DEBUG;
            case VERBOSE -> Level.TRACE;
        };
    }

    @Override
    public void logMessage(String fqcn, Level level, Marker marker, Message message, Throwable throwable) {
        if (allowed(level, marker)) super.logMessage(fqcn, level, marker, message, throwable);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, Message message, Throwable throwable) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, CharSequence message, Throwable throwable) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, Object message, Throwable throwable) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Throwable throwable) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object... params) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        return allowed(level, marker);
    }

    @Override
    public boolean isEnabled(Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
        return allowed(level, marker);
    }

}
