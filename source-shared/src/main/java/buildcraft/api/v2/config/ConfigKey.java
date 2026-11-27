package buildcraft.api.v2.config;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import buildcraft.api.v2.config.ConfigDefinition.Kind;

/** Typed view of a configuration definition; the existing canonical string schema remains authoritative. */
public final class ConfigKey<T> {
    private final ConfigDefinition definition;
    private final Class<T> type;
    private final Function<String, T> decoder;

    private ConfigKey(ConfigDefinition definition, Kind kind, Class<T> type, Function<String, T> decoder) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (definition.kind() != kind) throw new IllegalArgumentException("Expected " + kind + " for " + definition.id());
        this.type = type;
        this.decoder = decoder;
    }

    public static ConfigKey<Boolean> bool(ConfigDefinition definition) {
        return new ConfigKey<>(definition, Kind.BOOLEAN, Boolean.class, Boolean::valueOf);
    }

    /** For schemas whose entire accepted integer range fits into a Java int. */
    public static ConfigKey<Integer> integer(ConfigDefinition definition) {
        var key = new ConfigKey<>(definition, Kind.INTEGER, Integer.class, Integer::valueOf);
        if (Math.ceil(definition.minimum()) < Integer.MIN_VALUE || Math.floor(definition.maximum()) > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Integer schema exceeds int range: " + definition.id());
        return key;
    }

    public static ConfigKey<Long> longInteger(ConfigDefinition definition) {
        return new ConfigKey<>(definition, Kind.INTEGER, Long.class, Long::valueOf);
    }

    public static ConfigKey<Double> decimal(ConfigDefinition definition) {
        return new ConfigKey<>(definition, Kind.DECIMAL, Double.class, Double::valueOf);
    }

    public static ConfigKey<String> string(ConfigDefinition definition) {
        return new ConfigKey<>(definition, Kind.STRING, String.class, Function.identity());
    }

    /** Choices must be names of constants of the selected enum; subsets are supported. */
    public static <E extends Enum<E>> ConfigKey<E> enumeration(ConfigDefinition definition, Class<E> enumType) {
        Objects.requireNonNull(enumType, "enumType");
        var key = new ConfigKey<>(definition, Kind.ENUM, enumType, raw -> Enum.valueOf(enumType, raw));
        var names = Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
        if (!names.containsAll(definition.choices()))
            throw new IllegalArgumentException("Unknown enum constant in " + definition.id());
        return key;
    }

    public ConfigDefinition definition() { return definition; }
    public String id() { return definition.id(); }
    public Class<T> type() { return type; }
    public T defaultValue() { return parse(definition.defaultValue()); }
    public T parse(String value) { return decoder.apply(definition.validate(value)); }

    /** Presentation metadata/category may differ after registration; value constraints may not. */
    boolean matches(ConfigDefinition other) {
        return id().equals(other.id()) && definition.scope() == other.scope()
            && definition.kind() == other.kind() && definition.applyPolicy() == other.applyPolicy()
            && (definition.kind() != Kind.INTEGER && definition.kind() != Kind.DECIMAL
                || (Double.compare(definition.minimum(), other.minimum()) == 0
                    && Double.compare(definition.maximum(), other.maximum()) == 0))
            && (definition.kind() != Kind.ENUM || definition.choices().equals(other.choices()));
    }
}
