package buildcraft.lib.update;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native BCCE version scheme: generation.hundreds.version[.micropatch].
 *
 * <p>The short form {@code 8.1} denotes the first version after {@code 8.0.99};
 * it is numerically equivalent to {@code 8.0.100}. The optional fourth
 * component is a micropatch and is compared only after the base version.</p>
 */
public final class BCVersion implements Comparable<BCVersion> {
    private static final Pattern FORMAT = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+)(?:\\.(\\d+))?)?$");

    private final long generation;
    private final long absoluteVersion;
    private final long micropatch;
    private final String display;

    private BCVersion(long generation, long absoluteVersion, long micropatch) {
        this.generation = generation;
        this.absoluteVersion = absoluteVersion;
        this.micropatch = micropatch;
        this.display = canonical(generation, absoluteVersion, micropatch);
    }

    public static BCVersion parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Version is null");
        }
        Matcher matcher = FORMAT.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid BCCE version: " + value);
        }
        try {
            long generation = parsePositive(matcher.group(1), "generation", value);
            long hundreds = parseNonNegative(matcher.group(2), "hundreds", value);
            long version = matcher.group(3) == null ? 0 : parseNonNegative(matcher.group(3), "version", value);
            long micropatch = matcher.group(4) == null ? 0 : parseNonNegative(matcher.group(4), "micropatch", value);

            // Two-component versions are reserved for hundred-boundary shorthand (8.1 == 8.0.100).
            if (matcher.group(3) == null && hundreds == 0) {
                throw new IllegalArgumentException("Invalid BCCE boundary version: " + value);
            }
            long absolute = Math.addExact(Math.multiplyExact(hundreds, 100L), version);
            if (absolute <= 0) {
                throw new IllegalArgumentException("BCCE version must be positive: " + value);
            }
            return new BCVersion(generation, absolute, micropatch);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("BCCE version component overflow: " + value, exception);
        }
    }

    private static long parsePositive(String value, String component, String whole) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("BCCE " + component + " must be positive: " + whole);
        }
        return parsed;
    }

    private static long parseNonNegative(String value, String component, String whole) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("BCCE " + component + " must not be negative: " + whole);
        }
        return parsed;
    }

    private static String canonical(long generation, long absolute, long micropatch) {
        long hundreds = absolute / 100;
        long version = absolute % 100;
        String base = generation + "." + hundreds;
        if (version != 0 || micropatch != 0) {
            base += "." + version;
        }
        return micropatch == 0 ? base : base + "." + micropatch;
    }

    public String display() { return display; }
    public long generation() { return generation; }
    public long absoluteVersion() { return absoluteVersion; }
    public long hundreds() { return absoluteVersion / 100; }
    public long version() { return absoluteVersion % 100; }
    public long micropatch() { return micropatch; }

    @Override
    public int compareTo(BCVersion other) {
        int result = Long.compare(generation, other.generation);
        if (result == 0) result = Long.compare(absoluteVersion, other.absoluteVersion);
        if (result == 0) result = Long.compare(micropatch, other.micropatch);
        return result;
    }

    @Override public boolean equals(Object other) { return other instanceof BCVersion version && compareTo(version) == 0; }
    @Override public int hashCode() { return Objects.hash(generation, absoluteVersion, micropatch); }
    @Override public String toString() { return display; }
}
