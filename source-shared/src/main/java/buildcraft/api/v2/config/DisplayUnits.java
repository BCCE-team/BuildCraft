package buildcraft.api.v2.config;

import java.math.BigDecimal;
import java.util.Objects;

/** User-facing units only. Runtime amounts remain in micro-MJ, FE and millibuckets. */
public record DisplayUnits(RateUnit rate, FluidUnit fluid) {
    public static final String RATE_ID = "buildcraftcore:display/timeGap";
    public static final String FLUID_ID = "buildcraftcore:display/fluidVolumeUnit";
    public static final DisplayUnits DEFAULT = new DisplayUnits(RateUnit.TICKS, FluidUnit.MILLIBUCKETS);

    public DisplayUnits { Objects.requireNonNull(rate); Objects.requireNonNull(fluid); }
    public enum RateUnit {
        TICKS(1, "/t"), SECONDS(20, "/s");
        private final int ticks;
        private final String suffix;
        RateUnit(int ticks, String suffix) { this.ticks=ticks; this.suffix=suffix; }
        public int ticks() { return ticks; }
        public String suffix() { return suffix; }
        public BigDecimal convert(BigDecimal perTick) { return perTick.multiply(BigDecimal.valueOf(ticks)); }
    }
    public enum FluidUnit {
        BUCKETS("B", 3), MILLIBUCKETS("mB", 0);
        private final String symbol;
        private final int places;
        FluidUnit(String symbol, int places) { this.symbol=symbol; this.places=places; }
        public String symbol() { return symbol; }
        public BigDecimal convert(BigDecimal milliBuckets) { return milliBuckets.movePointLeft(places); }
    }
    private static String number(BigDecimal n) { return n.stripTrailingZeros().toPlainString(); }
    /** Format a quantity in the selected fluid unit, without rounding away small quantities. */
    public String formatFluid(long milliBuckets) { return number(fluid.convert(BigDecimal.valueOf(milliBuckets))) + " " + fluid.symbol(); }
    public String formatFluidRate(long milliBucketsPerTick) { return number(fluid.convert(rate.convert(BigDecimal.valueOf(milliBucketsPerTick)))) + " " + fluid.symbol() + rate.suffix(); }
    public String formatMjRate(long microMjPerTick) { return formatRate(BigDecimal.valueOf(microMjPerTick, 6), "MJ"); }
    public String formatFeRate(long fePerTick) { return formatRate(BigDecimal.valueOf(fePerTick), "FE"); }
    /** Also usable for items, blocks, and other addon-defined rates, expressed per game tick. */
    public String formatRate(BigDecimal perTick, String unit) { return number(rate.convert(perTick)) + " " + unit + rate.suffix(); }
}
