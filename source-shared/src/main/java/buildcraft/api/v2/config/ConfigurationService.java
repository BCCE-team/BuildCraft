package buildcraft.api.v2.config;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/** Public schema registration and effective-value access. Mutations remain server/client-authoritative.
 * In BCCE, effective-value and definition reads (including registered value handles) are synchronized;
 * registration must finish during loader bootstrap. Read server values only after its world is initialized.
 * Separate reads are not a transaction, and migration callbacks must not re-enter configuration or game services. */
public interface ConfigurationService {
    /** Register an addon-owned setting during module initialization. Select CLIENT, WORLD or BALANCE;
     * the registry prefixes the category with the addon namespace. The Addons editor is isolated from BCCE.
     * Registration ends at ApiLifecycle.FROZEN. LIVE values are supported for client settings;
     * WORLD_RESTART applies on the next client connection or server world start.
     * RESTART values are fixed for the process (dedicated server values initialize on first world start).
     * Read server handles after server world startup, and client handles at the point of use.
     * Server settings must use a restart policy. */
    Supplier<String> registerAddon(ConfigDefinition definition);
    /** Opt in to schema migrations before registering any definitions for this namespace/scope.
     * Each scope is versioned independently. Newer saved versions or failed migrations abort loading
     * rather than silently resetting data. Implementations predating this extension may reject it. */
    default void registerSchema(ConfigSchema schema) {
        throw new UnsupportedOperationException("Versioned configuration schemas are not supported by this runtime");
    }

    /** Typed registration using the same legacy schema and storage. Existing implementations remain binary compatible. */
    default <T> ConfigValue<T> register(ConfigKey<T> key) {
        Objects.requireNonNull(key, "key");
        return new ConfigValue<>(key, registerAddon(key.definition()));
    }

    /** Typed effective-value lookup. An absent ID is empty; a mismatched schema is a caller error. */
    default <T> Optional<T> read(ConfigKey<T> key) {
        Objects.requireNonNull(key, "key");
        for (ConfigDefinition definition : definitions()) {
            if (definition.id().equals(key.id())) {
                if (!key.matches(definition)) throw new IllegalArgumentException("Configuration schema mismatch: " + key.id());
                return value(key.id()).map(key::parse);
            }
        }
        return Optional.empty();
    }

    /** All locally registered schemas, not a remote server's schema or permission grant. */
    List<ConfigDefinition> definitions();
    /** Effective runtime value, which can differ from a saved restart-required value. */
    Optional<String> value(String id);

    /** Current local display preferences; read again when presenting data, not when simulating gameplay. */
    default DisplayUnits displayUnits() {
        return new DisplayUnits(value(DisplayUnits.RATE_ID).map(DisplayUnits.RateUnit::valueOf).orElse(DisplayUnits.DEFAULT.rate()),
            value(DisplayUnits.FLUID_ID).map(DisplayUnits.FluidUnit::valueOf).orElse(DisplayUnits.DEFAULT.fluid()));
    }

    /** Number and temperature presentation preferences. Existing DisplayUnits stays source/binary compatible. */
    default DisplayFormat displayFormat() {
        return new DisplayFormat(value(DisplayFormat.NUMBER_ID).map(DisplayFormat.NumberStyle::valueOf).orElse(DisplayFormat.DEFAULT.numbers()),
            value(DisplayFormat.TEMPERATURE_ID).map(DisplayFormat.TemperatureUnit::valueOf).orElse(DisplayFormat.DEFAULT.temperature()));
    }

    /** Resolve any registered client enum using ScreenMode choices (including addon-defined screens). */
    default ScreenMode screenMode(String id) {
        return value(id).map(ScreenMode::valueOf).orElse(ScreenMode.SWITCH);
    }
}
