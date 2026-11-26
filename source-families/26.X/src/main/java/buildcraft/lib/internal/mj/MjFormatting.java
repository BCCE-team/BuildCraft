package buildcraft.lib.internal.mj;


/** Internal formatting shortcut; public consumers use BuildCraftServices.MJ_FORMATTER directly. */
public final class MjFormatting {
    private MjFormatting() {}

    public static String formatMicroMj(long microMj) {
        return buildcraft.lib.internal.config.ClientPresentation.displayFormat().formatNumber(
            java.math.BigDecimal.valueOf(Math.max(0L, microMj), 6));
    }
}
