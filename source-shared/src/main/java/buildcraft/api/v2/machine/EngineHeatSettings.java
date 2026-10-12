package buildcraft.api.v2.machine;

import java.util.Objects;

/** Temperatures use BuildCraft degrees; cooling is degrees per server tick. */
public record EngineHeatSettings(HeatProfile operatingRange, double ambient, double critical,
    double passiveCoolingPerTick) {
    public EngineHeatSettings {
        Objects.requireNonNull(operatingRange, "operatingRange");
        if (!Double.isFinite(ambient) || !Double.isFinite(critical)
            || !Double.isFinite(passiveCoolingPerTick) || passiveCoolingPerTick < 0
            || ambient < operatingRange.minimum() || critical < operatingRange.maximum()) {
            throw new IllegalArgumentException("Invalid engine heat settings");
        }
    }
}
