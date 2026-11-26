package buildcraft.lib.client.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.DisplayUnits;
import buildcraft.lib.internal.mj.MjFeConversion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Read-only explanations of draft configuration values, not additional gameplay settings. */
final class ConfigurationCalculatedValues {
    private static final String RATIO = "buildcraftcore:power/mjPerFe";
    private static final String PIPE = "buildcrafttransport:general/pipes/";
    private static final String BALANCE = "buildcraft:balance/";
    private static final String PUMP = BALANCE + "machine/buildcraftfactory/pump/";
    private static final String QUARRY = BALANCE + "machine/buildcraftbuilders/quarry/";
    private static final BigDecimal TWENTY = BigDecimal.valueOf(20);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    record Line(String key, List<String> arguments) {}

    private ConfigurationCalculatedValues() {}

    private static Line line(String key, String... arguments) {
        return new Line(key, List.of(arguments));
    }
    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
    private static String quotient(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal result = numerator.divide(denominator, 6, RoundingMode.HALF_UP);
        return (result.multiply(denominator).compareTo(numerator) == 0 ? "" : "\u2248 ") + number(result);
    }
    private static String signedPercent(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + number(value) + "%";
    }
    private static BigDecimal decimal(Function<String, String> values, String id) {
        String value = values.apply(id);
        return value == null ? null : new BigDecimal(value);
    }
    private static BigDecimal configuredMjPerFe(BigDecimal value) {
        // Use the same rounding and bounds as the actual energy conversion.
        return BigDecimal.valueOf(MjFeConversion.createParsed(value.doubleValue()).mjPerFe, 6);
    }
    private static boolean multiplier(String id) {
        return id.startsWith(BALANCE) && (id.endsWith("/energy_cost_multiplier")
            || id.endsWith("/energy_multiplier") || id.endsWith("/work_speed_multiplier")
            || id.endsWith("/movement_speed_multiplier"));
    }
    static boolean supports(ConfigDefinition d) {
        if (d.isAddon() || (d.kind() != ConfigDefinition.Kind.INTEGER && d.kind() != ConfigDefinition.Kind.DECIMAL)) return false;
        String id = d.id();
        return multiplier(id) || switch (id) {
            case RATIO, PIPE + "mjPerItem", PIPE + "mjPerMillibucket", PIPE + "baseFluidRate", PIPE + "baseFeRate",
                PUMP + "energy_per_bucket_mj", QUARRY + "max_tasks_per_tick", QUARRY + "max_frame_speed",
                QUARRY + "max_mine_rate", QUARRY + "power_divisor", "buildcraftcore:general/itemLifespan",
                "buildcraftcore:general/updateFactor" -> true;
            default -> false;
        };
    }

    /** Values must be canonical, validated drafts from the same editor session (null if invalid or unavailable). */
    static List<Line> preview(ConfigDefinition d, Function<String, String> values, DisplayUnits units) {
        if (!supports(d)) return List.of();
        String id = d.id();
        BigDecimal value = decimal(values, id);
        if (value == null) return List.of(line("invalid"));
        List<Line> result = new ArrayList<>();
        if (multiplier(id)) {
            BigDecimal percent = value.multiply(HUNDRED);
            result.add(line("multiplier", number(percent) + "%", signedPercent(percent.subtract(HUNDRED))));
        }
        switch (id) {
            case RATIO -> {
                BigDecimal actual = configuredMjPerFe(value);
                String inverse = quotient(BigDecimal.ONE, actual);
                if (inverse.startsWith("\u2248 ")) result.add(line("mj_to_fe_approx", inverse.substring(2)));
                else result.add(line("mj_to_fe", inverse));
                result.add(line("fe_to_mj", number(actual)));
                if (actual.compareTo(value) != 0) result.add(line("ratio_rounded", number(actual)));
            }
            case PIPE + "mjPerItem" -> result.add(line("items_cost", number(value.movePointLeft(6).multiply(BigDecimal.valueOf(64)))));
            case PIPE + "mjPerMillibucket" -> result.add(line("bucket_cost", number(value.movePointLeft(3))));
            case PIPE + "baseFluidRate" -> {
                BigDecimal cost = decimal(values, PIPE + "mjPerMillibucket");
                if (cost != null) result.add(line("base_fluid_energy", units.formatRate(value.multiply(cost).movePointLeft(6), "MJ")));
                else result.add(line("invalid_dependency"));
            }
            case PIPE + "baseFeRate" -> {
                BigDecimal ratio = decimal(values, RATIO);
                if (ratio != null) result.add(line("base_fe_equivalent", units.formatRate(value.multiply(configuredMjPerFe(ratio)), "MJ")));
                else result.add(line("invalid_dependency"));
            }
            case PUMP + "energy_per_bucket_mj", PUMP + "energy_cost_multiplier" -> {
                BigDecimal base = decimal(values, PUMP + "energy_per_bucket_mj");
                BigDecimal multiplier = decimal(values, PUMP + "energy_cost_multiplier");
                if (base != null && multiplier != null) {
                    // Match the pump's micro-MJ rounding, but do not assume the API machine modifier is 1.
                    long microMj = Math.round(base.doubleValue() * 1_000_000L * multiplier.doubleValue());
                    result.add(line("pump_cycle", number(BigDecimal.valueOf(microMj, 6))));
                } else result.add(line("invalid_dependency"));
            }
            case QUARRY + "max_tasks_per_tick", QUARRY + "work_speed_multiplier" -> {
                BigDecimal base = decimal(values, QUARRY + "max_tasks_per_tick");
                BigDecimal speed = decimal(values, QUARRY + "work_speed_multiplier");
                if (base != null && speed != null) {
                    int attempts = Math.max(1, (int) (base.doubleValue() * speed.doubleValue()));
                    result.add(line("quarry_attempts", units.formatRate(BigDecimal.valueOf(attempts), "tasks")));
                } else result.add(line("invalid_dependency"));
            }
            case QUARRY + "max_frame_speed", QUARRY + "max_mine_rate" -> {
                if (value.signum() == 0) result.add(line("no_cap"));
                else {
                    BigDecimal speed = decimal(values, QUARRY + "work_speed_multiplier");
                    if (speed != null) result.add(line("quarry_cap", units.formatRate(value.multiply(speed).divide(TWENTY), "blocks")));
                    else result.add(line("invalid_dependency"));
                }
            }
            case QUARRY + "power_divisor" -> {
                if (value.signum() == 0) result.add(line("no_penalty"));
                else result.add(line("second_task", quotient(HUNDRED, value) + "%"));
            }
            case "buildcraftcore:general/itemLifespan" -> result.add(line("lifespan", number(value), number(value.multiply(TWENTY))));
            case "buildcraftcore:general/updateFactor" -> result.add(line("interval", number(value), number(value.divide(TWENTY))));
            default -> { }
        }
        return List.copyOf(result);
    }
}
