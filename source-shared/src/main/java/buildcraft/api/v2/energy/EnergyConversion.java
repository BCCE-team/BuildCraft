package buildcraft.api.v2.energy;

/** Exact rational boundary between BuildCraft MJ and Forge Energy units. */
public record EnergyConversion(long microMjPerFe) {
    public EnergyConversion {
        if (microMjPerFe <= 0) throw new IllegalArgumentException("microMjPerFe must be positive");
    }

    public long feToMicroMj(long fe) {
        if (fe < 0) throw new IllegalArgumentException("fe must be non-negative");
        return Math.multiplyExact(fe, microMjPerFe);
    }

    /** Saturating conversion for capacity/readability snapshots, never for actual transfers. */
    public long feToMicroMjClamped(long fe) {
        if (fe < 0) throw new IllegalArgumentException("fe must be non-negative");
        return fe > Long.MAX_VALUE / microMjPerFe ? Long.MAX_VALUE : fe * microMjPerFe;
    }

    /** Whole FE units that can be represented without creating energy. */
    public long microMjToWholeFe(long microMj) {
        if (microMj < 0) throw new IllegalArgumentException("microMj must be non-negative");
        return microMj / microMjPerFe;
    }

    public long conversionRemainder(long microMj) {
        if (microMj < 0) throw new IllegalArgumentException("microMj must be non-negative");
        return microMj % microMjPerFe;
    }
}
