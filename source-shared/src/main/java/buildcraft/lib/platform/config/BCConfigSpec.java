package buildcraft.lib.platform.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Internal schema and live value handles. No loader object is exposed to gameplay. */
public final class BCConfigSpec {
    /** Implemented only by the native config binding. Native validation/reload semantics remain authoritative. */
    public interface Backend {
        void push(String path);
        void pop();
        void comment(String... lines);
        void worldRestart();
        Supplier<Boolean> bool(String name, boolean value);
        Supplier<Integer> integer(String name, int value, int min, int max);
        Supplier<Double> decimal(String name, double value, double min, double max);
        Supplier<String> string(String name, String value);
        <E extends Enum<E>> Supplier<E> enumeration(String name, E value, E[] allowed);
    }
    private final List<Consumer<Backend>> steps;
    private boolean bound;
    private BCConfigSpec(List<Consumer<Backend>> steps) { this.steps = List.copyOf(steps); }

    /** Bind once during module construction, before loading a config. Values keep live native getters. */
    public synchronized void bind(Backend backend) {
        Objects.requireNonNull(backend, "backend");
        if (bound) throw new IllegalStateException("Config schema already bound");
        for (Consumer<Backend> step : steps) step.accept(backend);
        bound = true;
    }

    public static class ConfigValue<T> implements Supplier<T> {
        private Supplier<T> source;
        private void bindValue(Supplier<T> source) { this.source = Objects.requireNonNull(source); }
        @Override public T get() {
            if (source == null) throw new IllegalStateException("Config value read before native binding");
            return source.get();
        }
    }
    public static final class BooleanValue extends ConfigValue<Boolean> {}
    public static final class IntValue extends ConfigValue<Integer> {}
    public static final class DoubleValue extends ConfigValue<Double> {}
    public static final class EnumValue<E extends Enum<E>> extends ConfigValue<E> {}

    public static final class Builder {
        private final List<Consumer<Backend>> steps = new ArrayList<>();
        private boolean built;
        private Builder add(Consumer<Backend> step) {
            if (built) throw new IllegalStateException("Config builder already built");
            steps.add(step); return this;
        }
        public Builder push(String path) { Objects.requireNonNull(path); return add(b -> b.push(path)); }
        public Builder pop() { return add(Backend::pop); }
        public Builder comment(String... lines) {
            String[] copy = lines.clone(); return add(b -> b.comment(copy.clone()));
        }
        public Builder worldRestart() { return add(Backend::worldRestart); }
        public BooleanValue define(String key, boolean value) {
            BooleanValue entry = new BooleanValue();
            add(b -> ((ConfigValue<Boolean>) entry).bindValue(b.bool(key, value))); return entry;
        }
        public ConfigValue<String> define(String key, String value) {
            ConfigValue<String> entry = new ConfigValue<>();
            add(b -> entry.bindValue(b.string(key, value))); return entry;
        }
        public IntValue defineInRange(String key, int value, int min, int max) {
            IntValue entry = new IntValue();
            add(b -> ((ConfigValue<Integer>) entry).bindValue(b.integer(key, value, min, max))); return entry;
        }
        public DoubleValue defineInRange(String key, double value, double min, double max) {
            DoubleValue entry = new DoubleValue();
            add(b -> ((ConfigValue<Double>) entry).bindValue(b.decimal(key, value, min, max))); return entry;
        }
        @SafeVarargs public final <E extends Enum<E>> EnumValue<E> defineEnum(String key, E value, E... allowed) {
            E[] copy = allowed.clone(); EnumValue<E> entry = new EnumValue<>();
            add(b -> ((ConfigValue<E>) entry).bindValue(b.enumeration(key, value, copy.clone()))); return entry;
        }
        public BCConfigSpec build() {
            if (built) throw new IllegalStateException("Config builder already built");
            built = true; return new BCConfigSpec(steps);
        }
    }
}
