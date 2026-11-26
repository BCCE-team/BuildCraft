package buildcraft.api.v2.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Presentation only: never use these rounded strings as gameplay or configuration values. */
public record DisplayFormat(NumberStyle numbers, TemperatureUnit temperature) {
    public static final String NUMBER_ID = "buildcraftcore:display/numberFormat";
    public static final String TEMPERATURE_ID = "buildcraftcore:display/temperatureUnit";
    public static final DisplayFormat DEFAULT = new DisplayFormat(NumberStyle.GROUPED, TemperatureUnit.CELSIUS);
    public DisplayFormat { Objects.requireNonNull(numbers); Objects.requireNonNull(temperature); }
    public enum NumberStyle { PLAIN, GROUPED, COMPACT }
    public enum TemperatureUnit { KELVIN, CELSIUS, FAHRENHEIT }

    public String formatNumber(BigDecimal value) {
        Objects.requireNonNull(value);
        if (numbers == NumberStyle.COMPACT && value.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
            String[] suffix = {"", "K", "M", "B", "T", "P", "E"};
            int group = 0;
            BigDecimal scaled = value;
            while (group < suffix.length - 1 && scaled.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
                scaled = scaled.movePointLeft(3); group++;
            }
            scaled = scaled.setScale(2, RoundingMode.HALF_UP);
            if (group < suffix.length - 1 && scaled.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
                scaled = scaled.movePointLeft(3).setScale(2, RoundingMode.HALF_UP); group++;
            }
            return scaled.stripTrailingZeros().toPlainString() + suffix[group];
        }
        String plain = value.stripTrailingZeros().toPlainString();
        if (numbers != NumberStyle.GROUPED) return plain;
        int point = plain.indexOf('.');
        if (point < 0) point = plain.length();
        int start = plain.startsWith("-") ? 1 : 0;
        StringBuilder result = new StringBuilder(plain.length()+plain.length()/3);
        for (int i=0; i<plain.length(); i++) {
            if (i>start && i<point && (point-i)%3==0) result.append(' ');
            result.append(plain.charAt(i));
        }
        return result.toString();
    }

    public String formatFluid(DisplayUnits units, long milliBuckets) {
        return formatNumber(units.fluid().convert(BigDecimal.valueOf(milliBuckets))) + " " + units.fluid().symbol();
    }

    public String formatRate(DisplayUnits units, BigDecimal perTick, String unit) {
        return formatNumber(units.rate().convert(perTick)) + " " + unit + units.rate().suffix();
    }

    /** Absolute temperature; BCCE engine temperatures are stored in Celsius. */
    public String formatTemperature(double celsius) {
        if (!Double.isFinite(celsius)) return "--";
        BigDecimal c = BigDecimal.valueOf(celsius);
        BigDecimal converted = switch (temperature) {
            case CELSIUS -> c;
            case KELVIN -> c.add(new BigDecimal("273.15"));
            case FAHRENHEIT -> c.multiply(new BigDecimal("1.8")).add(BigDecimal.valueOf(32));
        };
        String unit = switch (temperature) { case CELSIUS -> "\u00b0C"; case KELVIN -> "K"; case FAHRENHEIT -> "\u00b0F"; };
        return formatNumber(converted.setScale(2, RoundingMode.HALF_UP)) + " " + unit;
    }
}
