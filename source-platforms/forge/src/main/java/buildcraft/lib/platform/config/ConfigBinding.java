package buildcraft.lib.platform.config;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** Native loader binding. The registered spec and all value getters retain the loader's validation/cache behavior. */
public final class ConfigBinding {
    private static final Map<BCConfigSpec, ForgeConfigSpec> SPECS = new IdentityHashMap<>();
    private ConfigBinding() {}
    public static synchronized ForgeConfigSpec bind(BCConfigSpec schema) {
        ForgeConfigSpec existing = SPECS.get(schema);
        if (existing != null) return existing;
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        schema.bind(new BCConfigSpec.Backend() {
            public void push(String path) { builder.push(path); }
            public void pop() { builder.pop(); }
            public void comment(String... lines) { builder.comment(lines); }
            public void worldRestart() { builder.worldRestart(); }
            public Supplier<Boolean> bool(String key, boolean value) { return builder.define(key, value)::get; }
            public Supplier<Integer> integer(String key, int value, int min, int max) { return builder.defineInRange(key, value, min, max)::get; }
            public Supplier<Double> decimal(String key, double value, double min, double max) { return builder.defineInRange(key, value, min, max)::get; }
            public Supplier<String> string(String key, String value) { return builder.define(key, value)::get; }
            public <E extends Enum<E>> Supplier<E> enumeration(String key, E value, E[] allowed) {
                return allowed.length == 0 ? builder.defineEnum(key, value)::get : builder.defineEnum(key, value, allowed)::get;
            }
        });
        ForgeConfigSpec nativeSpec = builder.build(); SPECS.put(schema, nativeSpec); return nativeSpec;
    }
    public static void listen(IEventBus bus, Consumer<String> onLoad, Consumer<String> onReload) {
        bus.addListener((ModConfigEvent.Loading event) -> onLoad.accept(event.getConfig().getModId()));
        bus.addListener((ModConfigEvent.Reloading event) -> onReload.accept(event.getConfig().getModId()));
    }
}
