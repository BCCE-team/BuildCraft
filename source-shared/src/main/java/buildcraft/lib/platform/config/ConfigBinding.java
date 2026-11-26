package buildcraft.lib.platform.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Translates the existing module schemas into BCCE's own registry, not a loader config spec. */
public final class ConfigBinding {
    private static final Map<String, Consumer<String>> RELOAD = new LinkedHashMap<>();
    private static final Set<BCConfigSpec> BOUND = Collections.newSetFromMap(new IdentityHashMap<>());
    private ConfigBinding() {}

    public static synchronized void bind(String module, BCConfigSpec schema, Consumer<String> reload) {
        if (BOUND.contains(schema) || RELOAD.containsKey(module)) throw new IllegalStateException("Already bound: " + module);
        Map<String, String> old = Map.of();
        Path legacy = Path.of("config", module + "-common.toml");
        try { if (Files.isRegularFile(legacy)) old = LegacyToml.parseToml(Files.readAllLines(legacy)); }
        catch (IOException e) { System.err.println("[BCCE/Config] Cannot import " + legacy + ": " + e.getMessage()); }
        schema.bind(new SchemaBackend(module, old));
        BOUND.add(schema); RELOAD.put(module, reload);
    }

    /** Called on the server lifecycle thread before the world loads. */
    public static synchronized void refreshServerValues() {
        RELOAD.forEach((module, listener) -> listener.accept(module));
    }

    private static final class SchemaBackend implements BCConfigSpec.Backend {
        private final String module;
        private final Map<String, String> old;
        private final Deque<String> path = new ArrayDeque<>();
        private List<String> comments = List.of();
        private boolean restart;
        SchemaBackend(String module, Map<String, String> old) { this.module = module; this.old = old; }
        public void push(String section) { path.addLast(section); }
        public void pop() { path.removeLast(); }
        public void comment(String... lines) { comments = List.of(lines.clone()); }
        public void worldRestart() { restart = true; }

        private <T> Supplier<T> add(String name, T defaultValue, Kind kind, double min, double max,
                                     List<String> choices, Function<String,T> parser) {
            String full = (path.isEmpty() ? "" : String.join(".", path) + ".") + name;
            String id = module + ":" + full.replace('.', '/');
            String nameKey = "config." + module + "." + full;
            Scope scope = scope(module, full);
            Section section = scope == Scope.CLIENT ? Section.CLIENT : isBalance(module, full) ? Section.BALANCE : Section.WORLD;
            List<String> category = category(module, full, section);
            String fallbackName = human(name.substring(name.lastIndexOf('.') + 1));
            String shortDescription = comments.isEmpty() ? fallbackName : comments.get(0);
            String fullDescription = comments.isEmpty() ? fallbackName : String.join("\n", comments);
            String raw = old.get(full);
            if (raw != null) {
                try {
                    if (kind == Kind.STRING || kind == Kind.ENUM) raw = LegacyToml.readString(raw);
                    else if (kind == Kind.INTEGER || kind == Kind.DECIMAL) raw = raw.replace("_", "");
                } catch (IllegalArgumentException e) { raw = null; }
            }
            if (module.equals("buildcraftcore") && full.equals("display.fluidVolumeUnit")) {
                // New key wins; otherwise prefer the old static choice, then the old flow choice.
                var registry = ConfigurationRegistry.INSTANCE;
                String legacy = registry.savedClientValue(module+":display/useBucketsStatic")
                    .orElseGet(() -> registry.savedClientValue(module+":display/useBucketsFlow")
                        .orElse(old.getOrDefault("display.useBucketsStatic", old.get("display.useBucketsFlow"))));
                if (raw == null && legacy != null) {
                    if (legacy.equalsIgnoreCase("true")) raw = "BUCKETS";
                    else if (legacy.equalsIgnoreCase("false")) raw = "MILLIBUCKETS";
                }
            }
            // Treat other current module values as lifecycle-bound: several are copied into
            // static fields, caches, capabilities and world generation workers.
            ApplyPolicy policy = scope == Scope.CLIENT ? ApplyPolicy.RESTART : ApplyPolicy.WORLD_RESTART;
            if (module.equals("buildcraftcore") && Set.of("display.timeGap", "display.fluidVolumeUnit",
                    "display.zonePlannerMode", "display.electronicLibraryMode").contains(full)) policy = ApplyPolicy.LIVE;
            ConfigDefinition def = new ConfigDefinition(id, section, category, scope, kind,
                defaultValue instanceof Enum<?> e ? e.name() : String.valueOf(defaultValue), min, max, choices,
                nameKey + ".name", nameKey + ".description", nameKey + ".tooltip", fallbackName,
                shortDescription, fullDescription, policy);
            Supplier<String> handle = ConfigurationRegistry.INSTANCE.register(def, raw);
            comments = List.of(); restart = false;
            return () -> parser.apply(handle.get());
        }
        public Supplier<Boolean> bool(String name, boolean value) { return add(name,value,Kind.BOOLEAN,0,0,List.of(),Boolean::parseBoolean); }
        public Supplier<Integer> integer(String name, int value, int min, int max) { return add(name,value,Kind.INTEGER,min,max,List.of(),Integer::parseInt); }
        public Supplier<Double> decimal(String name, double value, double min, double max) { return add(name,value,Kind.DECIMAL,min,max,List.of(),Double::parseDouble); }
        public Supplier<String> string(String name, String value) { return add(name,value,Kind.STRING,0,0,List.of(),Function.identity()); }
        public <E extends Enum<E>> Supplier<E> enumeration(String name, E value, E[] allowed) {
            E[] values = allowed.length == 0 ? value.getDeclaringClass().getEnumConstants() : allowed;
            return add(name,value,Kind.ENUM,0,0,Arrays.stream(values).map(Enum::name).toList(),s -> Enum.valueOf(value.getDeclaringClass(),s));
        }
    }

    private static Scope scope(String module, String key) {
        if (key.startsWith("display.")) return Scope.CLIENT;
        if (module.equals("buildcraftcore") && Set.of("general.enableAnimatedSprites", "general.maxGuideSearchResults", "general.guideItemSearchLimit").contains(key)) return Scope.CLIENT;
        return Scope.SERVER;
    }
    private static boolean isBalance(String module, String key) {
        return module.equals("buildcrafttransport") && key.startsWith("general.pipes.")
            || module.equals("buildcraftcore") && key.equals("power.mjPerFe");
    }
    private static List<String> category(String module, String key, Section section) {
        if (section == Section.CLIENT) {
            if (module.equals("buildcraftcore")) {
                if (Set.of("display.zonePlannerMode", "display.electronicLibraryMode").contains(key)) return List.of("Interface", "Screens");
                if (Set.of("display.timeGap", "display.fluidVolumeUnit").contains(key)) return List.of("Interface", "Units");
            }
            if (key.contains("colorBlind") || key.contains("Contrast")) return List.of("Accessibility");
            if (key.contains("Guide") || key.contains("guide")) return List.of("Interface", "Guide");
            if (key.contains("Sprites") || key.contains("RenderRotation") || key.startsWith("display.pipes.")) return List.of("Rendering");
            return List.of("Interface");
        }
        if (section == Section.BALANCE) return module.equals("buildcrafttransport") ? List.of("Pipes") : List.of("Energy");
        if (key.startsWith("worldgen.")) return key.startsWith("worldgen.oil.spouts.") ? List.of("World Generation", "Oil", "Spouts") : List.of("World Generation", "Oil");
        if (key.equals("general.useWrenchTag") || key.equals("power.powerMode")) return List.of("Compatibility");
        if (key.contains("chunkLoad") || key.contains("updateFactor")) return List.of("Performance");
        if (key.startsWith("debug.")) return List.of("Diagnostics");
        if (key.startsWith("engines.")) return List.of("Operating Modes", "Engines");
        if (key.startsWith("facades.")) return List.of("Operating Modes", "Facades");
        return List.of("Gameplay Rules");
    }
    public static String human(String key) {
        String text = key.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return text.isEmpty() ? key : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
