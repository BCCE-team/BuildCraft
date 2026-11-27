package buildcraft.lib.internal.config;

import buildcraft.api.v2.config.DisplayFormat;
import java.math.BigDecimal;

/** Live client preferences; no gameplay simulation or server state is changed. */
public final class ClientPresentation {
    private static final String DISPLAY = "buildcraftcore:display/";
    public enum PipeItems { ALL, SIMPLIFIED, DISABLED }
    private ClientPresentation() {}
    private static String value(String key, String fallback) {
        return ConfigurationRegistry.INSTANCE.value(DISPLAY + key).orElse(fallback);
    }
    public static DisplayFormat displayFormat() { return ConfigurationRegistry.INSTANCE.displayFormat(); }
    public static String number(long value) { return displayFormat().formatNumber(BigDecimal.valueOf(value)); }
    public static String number(double value) {
        return Double.isFinite(value) ? displayFormat().formatNumber(BigDecimal.valueOf(value)) : "--";
    }
    public static String fluid(long amount) { return displayFormat().formatFluid(ConfigurationRegistry.INSTANCE.displayUnits(), amount); }
    public static String feRate(long amount) { return displayFormat().formatRate(ConfigurationRegistry.INSTANCE.displayUnits(), BigDecimal.valueOf(amount), "FE"); }
    public static String temperature(double celsius) { return displayFormat().formatTemperature(celsius); }
    public static boolean animatePipeFluids() { return Boolean.parseBoolean(value("pipes/fluidAnimation", "true")); }
    public static int pipeDistance() { return Integer.parseInt(value("pipes/contentRenderDistance", "64")); }
    public static int pipeItemLimit() {
        return switch (PipeItems.valueOf(value("pipes/itemDisplay", "ALL"))) {
            case DISABLED -> 0;
            case SIMPLIFIED -> 1;
            case ALL -> Integer.parseInt(value("pipes/maxRenderedItems", "64"));
        };
    }
}
