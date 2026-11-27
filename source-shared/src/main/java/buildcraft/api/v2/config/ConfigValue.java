package buildcraft.api.v2.config;

import java.util.Objects;
import java.util.function.Supplier;

/** Read-only typed handle to the current effective value, not a mutable or saved-value snapshot. */
public final class ConfigValue<T> implements Supplier<T> {
    private final ConfigKey<T> key;
    private final Supplier<String> source;

    ConfigValue(ConfigKey<T> key, Supplier<String> source) {
        this.key = Objects.requireNonNull(key, "key");
        this.source = Objects.requireNonNull(source, "source");
    }

    public ConfigKey<T> key() { return key; }
    @Override public T get() { return key.parse(source.get()); }
}
