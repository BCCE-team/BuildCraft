/** Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License. Please check the contents of the license, which
 * should be located as "LICENSE.API" in the BuildCraft source code distribution. */
package buildcraft.lib.internal.debug;

import buildcraft.lib.internal.core.BuildCraftAPI;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.Logger;

public final class BCLog {
    public static final Marker DIAGNOSTIC_MARKER = MarkerManager.getMarker("BCCE_DIAGNOSTIC");
    public static final Marker EXPLICIT_MARKER = MarkerManager.getMarker("BCCE_EXPLICIT_DEBUG");
    public static final Logger logger = BCProfileLogger.create();
    private static final ConcurrentHashMap<String, AtomicLong> SAMPLED = new ConcurrentHashMap<>();
    private static final int MAX_SAMPLE_KEYS = 128;

    static {
        if (BCLogProfile.configurationWarning() != null) {
            logger.warn(BCLogProfile.configurationWarning());
        }
    }

    /** Fallback, repair, invalid-but-recoverable input. Hidden in the default profile. */
    public static void diagnostic(String message, Object... arguments) {
        if (BCLogProfile.atLeast(BCLogProfile.Profile.DEBUG)) {
            logger.info(DIAGNOSTIC_MARKER, message, arguments);
        }
    }

    /** Major state transition, assignment, completion, or routing decision. */
    public static void event(String message, Object... arguments) {
        if (BCLogProfile.atLeast(BCLogProfile.Profile.HARD_DEBUGGING)) {
            logger.info(message, arguments);
        }
    }

    /** Supports the existing explicit verboseRobotLogging setting in every profile. */
    public static void explicitEvent(String message, Object... arguments) {
        logger.info(EXPLICIT_MARKER, message, arguments);
    }

    /**
     * Sampling is per stable category, NEVER per position or user-controlled payload.
     * The first three occurrences and each 128th are emitted. VERBOSE emits all.
     */
    private static long sample(String category) {
        if (BCLogProfile.current() == BCLogProfile.Profile.VERBOSE) return 1;
        AtomicLong counter = SAMPLED.get(category);
        if (counter == null) {
            if (SAMPLED.size() >= MAX_SAMPLE_KEYS) return 0;
            counter = SAMPLED.computeIfAbsent(category, ignored -> new AtomicLong());
        }
        long count = counter.incrementAndGet();
        return count <= 3 || count % 128 == 0 ? count : 0;
    }

    public static void diagnosticSampled(String category, String message, Object... arguments) {
        if (!BCLogProfile.atLeast(BCLogProfile.Profile.DEBUG)) return;
        long occurrence = sample("diagnostic:" + category);
        if (occurrence > 0) diagnostic("[{} #{}] " + message, prepend(category, occurrence, arguments));
    }

    public static void warningSampled(String category, String message, Throwable error) {
        long occurrence = sample("warning:" + category);
        if (occurrence > 0) {
            logger.warn("[" + category + " #" + occurrence + "] " + message, error);
        }
    }

    private static Object[] prepend(String category, long occurrence, Object[] arguments) {
        Object[] result = new Object[arguments.length + 2];
        result[0] = category;
        result[1] = occurrence;
        System.arraycopy(arguments, 0, result, 2, arguments.length);
        return result;
    }
    
    /** Deactivate constructor */
    private BCLog() {}

    @Deprecated
    public static void logErrorAPI(String mod, Throwable error, Class<?> classFile) {
        logErrorAPI(error, classFile);
        
     
    }

    public static void logErrorAPI(Throwable error, Class<?> classFile) {
        StringBuilder msg = new StringBuilder("API error! Please update your mods. Error: ");
        msg.append(error);
        StackTraceElement[] stackTrace = error.getStackTrace();
        if (stackTrace.length > 0) {
            msg.append(", ").append(stackTrace[0]);
        }
        
        logger.error(msg.toString());

        if (classFile != null) {
            msg.append("API error: ").append(classFile.getSimpleName()).append(" is loaded from ").append(classFile.getProtectionDomain()
                    .getCodeSource().getLocation());
            logger.error(msg.toString());
        }
    }

    @Deprecated
    public static String getVersion() {
        return BuildCraftAPI.getVersion();
    }
    
    public static void d(String s) {
    	logger.debug(new Exception().getStackTrace()[1].getClassName() + " : " + s);
    }
    
    public static void d(boolean flag) {
    	if(flag)
    		logger.debug(new Exception().getStackTrace()[1].getClassName() + " : Catched!");
    }
    
    public static void d(boolean flag, String msg) {
    	if(flag)
    		logger.debug(new Exception().getStackTrace()[1].getClassName() + " : "+msg);
    }
}
