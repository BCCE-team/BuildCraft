package buildcraft.api.v2.fuels;

import buildcraft.api.v2.fluid.FluidVariantData;

/** Data representation for the stable built-in FuelProfile form. */
public record FuelProfileData(
    FluidSelectorData selector,
    long powerPerTickMicroMj,
    int burnTicksPerBucket,
    FluidVariantData residueVariant,
    long residueMilliBuckets,
    /** Optional for compatibility with legacy fuel profiles that assumed heat equals power. */
    Long heatPerTickMicroMj
) {
    /** Source-compatible constructor for API v2 clients using the original five data fields. */
    public FuelProfileData(FluidSelectorData selector, long powerPerTickMicroMj, int burnTicksPerBucket,
            FluidVariantData residueVariant, long residueMilliBuckets) {
        this(selector, powerPerTickMicroMj, burnTicksPerBucket, residueVariant, residueMilliBuckets, null);
    }
}
