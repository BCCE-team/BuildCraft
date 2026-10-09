package buildcraft.lib.internal.mj;

import buildcraft.api.v2.energy.MjAmount;

/** Immutable MJ <-> Forge Energy conversion ratio. */
public final class MjFeConversion {
    /** Maximum MJ per FE, or minimum of 5 FE to make 1 MJ. */
    public static final long MAX_MJ_PER_FE = MjAmount.MICRO_MJ_PER_MJ / 5;
    /** Minimum MJ per FE, or maximum of 10,000 FE to make 1 MJ. */
    public static final long MIN_MJ_PER_FE = MjAmount.MICRO_MJ_PER_MJ / 10_000;
    /** Original BuildCraft 8 default: 1 MJ = 10 FE. */
    public static final long DEFAULT_MJ_PER_FE = MjAmount.MICRO_MJ_PER_MJ / 10;

    public final long mjPerFe;
    public final boolean usingDefaultValue;

    private MjFeConversion(long mjPerFe) {
        if (MIN_MJ_PER_FE <= mjPerFe && mjPerFe <= MAX_MJ_PER_FE) {
            this.mjPerFe = mjPerFe;
            this.usingDefaultValue = false;
        } else {
            this.mjPerFe = DEFAULT_MJ_PER_FE;
            this.usingDefaultValue = true;
        }
    }

    public static MjFeConversion createRaw(long mjPerFe) {
        return new MjFeConversion(mjPerFe);
    }

    /** @param configMjPerFe MJ per 1 FE. Rounded to the nearest 100 micro-MJ. */
    public static MjFeConversion createParsed(double configMjPerFe) {
        long value = Math.round(configMjPerFe * 10_000);
        return new MjFeConversion(value * MjAmount.MICRO_MJ_PER_MJ / 10_000);
    }

    public static MjFeConversion createDefault() {
        return new MjFeConversion(DEFAULT_MJ_PER_FE);
    }

    public long feToMicroMj(long fe) {
        if (fe <= 0) return 0;
        if (fe > Long.MAX_VALUE / mjPerFe) return Long.MAX_VALUE;
        return fe * mjPerFe;
    }

    public long microMjToFe(long microMj) {
        return microMj <= 0 ? 0 : microMj / mjPerFe;
    }
}
