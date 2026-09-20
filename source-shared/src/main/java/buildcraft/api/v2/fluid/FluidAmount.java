package buildcraft.api.v2.fluid;

import java.util.Objects;

/**
 * Non-negative fluid amount measured in milliBuckets (mB).
 *
 * The stable API uses BuildCraft's canonical mB unit with a wide integer
 * representation. Platform bridges must report any
 * conversion remainder rather than silently rounding.
 */
public final class FluidAmount implements Comparable<FluidAmount> {
    public static final FluidAmount ZERO = new FluidAmount(0);
    public static final long MILLIBUCKETS_PER_BUCKET = 1000L;

    private final long milliBuckets;

    private FluidAmount(long milliBuckets) {
        this.milliBuckets = milliBuckets;
    }

    public static FluidAmount of(long milliBuckets) {
        if (milliBuckets < 0) {
            throw new IllegalArgumentException("Fluid amount must be non-negative: " + milliBuckets);
        }
        return milliBuckets == 0 ? ZERO : new FluidAmount(milliBuckets);
    }

    public long milliBuckets() {
        return milliBuckets;
    }

    public boolean isZero() {
        return milliBuckets == 0;
    }

    public FluidAmount plus(FluidAmount other) {
        Objects.requireNonNull(other, "other");
        return of(Math.addExact(milliBuckets, other.milliBuckets));
    }

    public FluidAmount minus(FluidAmount other) {
        Objects.requireNonNull(other, "other");
        long result = Math.subtractExact(milliBuckets, other.milliBuckets);
        if (result < 0) {
            throw new IllegalArgumentException(
                "Fluid amount subtraction would become negative: " + milliBuckets + " - " + other.milliBuckets
            );
        }
        return of(result);
    }

    public FluidAmount min(FluidAmount other) {
        Objects.requireNonNull(other, "other");
        return milliBuckets <= other.milliBuckets ? this : other;
    }

    @Override
    public int compareTo(FluidAmount other) {
        return Long.compare(milliBuckets, Objects.requireNonNull(other, "other").milliBuckets);
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || obj instanceof FluidAmount other && milliBuckets == other.milliBuckets;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(milliBuckets);
    }

    @Override
    public String toString() {
        return milliBuckets + " mB";
    }
}
