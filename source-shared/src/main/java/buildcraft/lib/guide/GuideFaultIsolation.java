package buildcraft.lib.guide;

import buildcraft.lib.internal.debug.BCLog;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Isolates optional third-party Guide Book data at API boundaries.
 *
 * <p>Recipes, displays and addon pages are extensions, not prerequisites for
 * opening a world or a reference book. Failure of one entry must not abort the
 * entire index. Does not suppress fatal JVM errors such as OutOfMemoryError.</p>
 */
public final class GuideFaultIsolation {
    private static final int MAX_DISTINCT_WARNINGS = 32;
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger warningCount = new AtomicInteger();

    private GuideFaultIsolation() {}

    public static <T> T value(String operation, String id, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (RuntimeException | LinkageError exception) {
            warn(operation, id, exception);
            return fallback;
        }
    }

    public static void warn(String operation, String id, Throwable failure) {
        // Bound both emitted log messages and the in-memory deduplication set.
        // Some modpacks contain tens of thousands of invalid addon entries.
        if (warningCount.get() > MAX_DISTINCT_WARNINGS) return;
        String key = operation + ':' + id;
        if (!warned.add(key)) return;
        int count = warningCount.incrementAndGet();
        if (count <= MAX_DISTINCT_WARNINGS) {
            BCLog.logger.warn("[lib.guide] Skipping unsupported {} {}", operation, id, failure);
        } else if (count == MAX_DISTINCT_WARNINGS + 1) {
            BCLog.logger.warn("[lib.guide] Further unsupported entry warnings suppressed");
        }
    }
}
