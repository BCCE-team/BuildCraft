package buildcraft.energy.generation.features;

/**
 * User-facing oil frequency presets. The stock datapack uses a base oil-well
 * generation rate of 0.3. A preset scales that rate, rather than changing the
 * placed-feature count (which would cut off multi-chunk deposits).
 *
 * Custom datapacks keep their relative frequency adjustments: their configured
 * oilWellGenerationRate is multiplied by the selected profile's multiplier.
 */
public enum OilGenerationProfile {
    STANDARD(0.3D),
    HIGH(0.6D),
    VERY_HIGH(1.0D);

    private final double rate;

    OilGenerationProfile(double rate) {
        this.rate = rate;
    }

    /** Effective oilWellGenerationRate with the stock BCCE datapack. */
    public double rate() {
        return rate;
    }

    /** Multiplier relative to the stock STANDARD 0.3 worldgen resource. */
    public double multiplier() {
        return rate / STANDARD.rate;
    }
}
