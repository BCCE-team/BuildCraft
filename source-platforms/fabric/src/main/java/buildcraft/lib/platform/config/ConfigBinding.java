package buildcraft.lib.platform.config;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Fabric binding for loader-neutral BuildCraft config schemas. */
public final class ConfigBinding {
    private static final Map<BCConfigSpec, FabricSpec> SPECS = new IdentityHashMap<>();
    private ConfigBinding() { }

    public static synchronized FabricSpec bind(BCConfigSpec schema) {
        FabricSpec existing = SPECS.get(schema);
        if (existing != null) return existing;
        FabricSpec spec = new FabricSpec();
        schema.bind(spec);
        SPECS.put(schema, spec);
        return spec;
    }

    public static void listen(Consumer<String> onLoad, Consumer<String> onReload) {
        FabricConfigEvents.add(onLoad, onReload);
    }

    public static final class FabricSpec implements BCConfigSpec.Backend {
        private String prefix = "";
        private final Map<String, Object> values = new java.util.LinkedHashMap<>();

        @Override public void push(String path) { prefix = prefix.isEmpty() ? path : prefix + "." + path; }
        @Override public void pop() {
            int split = prefix.lastIndexOf('.');
            prefix = split < 0 ? "" : prefix.substring(0, split);
        }
        @Override public void comment(String... lines) { }
        @Override public void worldRestart() { }
        @Override public Supplier<Boolean> bool(String name, boolean value) { return entry(name, value); }
        @Override public Supplier<Integer> integer(String name, int value, int min, int max) {
            if (value < min || value > max) throw new IllegalArgumentException(name + " default outside range");
            return entry(name, value);
        }
        @Override public Supplier<Double> decimal(String name, double value, double min, double max) {
            if (value < min || value > max) throw new IllegalArgumentException(name + " default outside range");
            return entry(name, value);
        }
        @Override public Supplier<String> string(String name, String value) { return entry(name, value); }
        @Override public <E extends Enum<E>> Supplier<E> enumeration(String name, E value, E[] allowed) { return entry(name, value); }

        @SuppressWarnings("unchecked")
        private <T> Supplier<T> entry(String name, T defaultValue) {
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            values.putIfAbsent(key, defaultValue);
            return () -> (T) values.get(key);
        }
    }
}
