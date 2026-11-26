package buildcraft.api.v2.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Public schema registration and effective-value access. Mutations remain server/client-authoritative. */
public interface ConfigurationService {
    /** Register an addon-owned setting during module initialization. Select CLIENT, WORLD or BALANCE;
     * the registry prefixes the category with the addon namespace. The Addons editor is isolated from BCCE.
     * LIVE values are supported for client settings; read the returned supplier when presenting a value.
     * Server settings must use a restart policy. */
    Supplier<String> registerAddon(ConfigDefinition definition);
    /** All locally registered schemas, not a remote server's schema or permission grant. */
    List<ConfigDefinition> definitions();
    /** Effective runtime value, which can differ from a saved restart-required value. */
    Optional<String> value(String id);

    /** Current local display preferences; read again when presenting data, not when simulating gameplay. */
    default DisplayUnits displayUnits() {
        return new DisplayUnits(value(DisplayUnits.RATE_ID).map(DisplayUnits.RateUnit::valueOf).orElse(DisplayUnits.DEFAULT.rate()),
            value(DisplayUnits.FLUID_ID).map(DisplayUnits.FluidUnit::valueOf).orElse(DisplayUnits.DEFAULT.fluid()));
    }

    /** Resolve any registered client enum using ScreenMode choices (including addon-defined screens). */
    default ScreenMode screenMode(String id) {
        return value(id).map(ScreenMode::valueOf).orElse(ScreenMode.SWITCH);
    }
}
