package buildcraft.api.v2.pipe;

/**
 * Effective transport configuration after applying the built-in pipe definition and behaviour hooks.
 * For MJ, lossPerTick and resistancePpm may be -1 to request the native derived default.
 * FE has no native resistance and uses -1 for both loss fields.
 */
public record PowerFlowSettings(PipeMedium medium, long maxPerTick, long lossPerTick,
                                long resistancePpm, boolean receiver, boolean disabled) {
    public PowerFlowSettings {
        if (medium != PipeMedium.MJ && medium != PipeMedium.EXTERNAL_ENERGY)
            throw new IllegalArgumentException("Not a power medium: " + medium);
        if (maxPerTick <= 0 || (medium == PipeMedium.EXTERNAL_ENERGY && maxPerTick > Integer.MAX_VALUE)
            || (medium == PipeMedium.MJ && maxPerTick > Long.MAX_VALUE / 1_000_000L))
            throw new IllegalArgumentException("invalid transfer rate");
        if (lossPerTick < -1 || resistancePpm < -1 ||
            (medium == PipeMedium.EXTERNAL_ENERGY && (lossPerTick != -1 || resistancePpm != -1)) ||
            (medium == PipeMedium.MJ && resistancePpm > 1_000_000L))
            throw new IllegalArgumentException("invalid loss/resistance");
    }
}
