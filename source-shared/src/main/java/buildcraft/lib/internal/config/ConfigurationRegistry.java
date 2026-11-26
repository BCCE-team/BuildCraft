package buildcraft.lib.internal.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.Scope;
import buildcraft.api.v2.config.ConfigurationService;
import buildcraft.api.v2.config.DisplayUnits;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** BCCE-owned storage. Validation and optimistic concurrency precede the single atomic file replacement. */
public final class ConfigurationRegistry implements ConfigurationService {
    public static final ConfigurationRegistry INSTANCE = new ConfigurationRegistry(Path.of("config", "buildcraft"));
    private final Path directory;
    private final Map<String, ConfigDefinition> definitions = new LinkedHashMap<>();
    private final EnumMap<Scope, Map<String, String>> saved = new EnumMap<>(Scope.class);
    private final Map<String, String> active = new HashMap<>();
    private final EnumMap<Scope, Long> revisions = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, IOException> readErrors = new EnumMap<>(Scope.class);
    private Path worldFile;
    private Map<String, String> serverDefaults;
    private boolean localWorld;

    public ConfigurationRegistry(Path directory) {
        this.directory = directory;
        for (Scope scope : Scope.values()) {
            Map<String, String> values;
            try { values = read(globalFile(scope)); }
            catch (IOException e) { readErrors.put(scope, e); values = new LinkedHashMap<>(); }
            saved.put(scope, values); revisions.put(scope, 0L);
        }
    }

    private Path globalFile(Scope scope) {
        return directory.resolve(scope == Scope.CLIENT ? "client.properties" : "server-defaults.properties");
    }
    private Path file(Scope scope) { return scope == Scope.SERVER && worldFile != null ? worldFile : globalFile(scope); }

    @Override public synchronized List<ConfigDefinition> definitions() { return List.copyOf(definitions.values()); }
    @Override public synchronized Optional<String> value(String id) { return Optional.ofNullable(active.get(id)); }
    @Override public synchronized DisplayUnits displayUnits() { return ConfigurationService.super.displayUnits(); }

    @Override public synchronized Supplier<String> registerAddon(ConfigDefinition definition) {
        if (ConfigDefinition.isBuiltInNamespace(definition.ownerNamespace()))
            throw new IllegalArgumentException("Addon settings must use an addon namespace");
        if (definition.scope() == Scope.SERVER && worldFile != null)
            throw new IllegalStateException("Register server configuration before a world starts");
        // Client LIVE settings are read through their effective-value handle. Server hot reload needs
        // a separate migration contract for inventories and running machines.
        if (definition.scope() == Scope.SERVER && definition.applyPolicy() == ConfigDefinition.ApplyPolicy.LIVE)
            throw new IllegalArgumentException("Live server addon mutations are not supported yet");
        List<String> path=new ArrayList<>(); path.add(definition.ownerNamespace()); path.addAll(definition.category());
        ConfigDefinition grouped=new ConfigDefinition(definition.id(),definition.editorSection(),path,definition.scope(),definition.kind(),definition.defaultValue(),definition.minimum(),definition.maximum(),definition.choices(),definition.nameKey(),definition.descriptionKey(),definition.tooltipKey(),definition.fallbackName(),definition.fallbackDescription(),definition.fallbackTooltip(),definition.applyPolicy());
        return register(grouped, null);
    }

    /** Internal migration access to an unregistered legacy key; never exposes remote values. */
    public synchronized Optional<String> savedClientValue(String id) { return Optional.ofNullable(saved.get(Scope.CLIENT).get(id)); }

    public synchronized Supplier<String> register(ConfigDefinition def, String migratedValue) {
        if (definitions.size()>=2048) throw new IllegalStateException("Too many configuration definitions");
        if (definitions.containsKey(def.id())) throw new IllegalArgumentException("Duplicate setting: " + def.id());
        Map<String, String> values = saved.get(def.scope());
        String raw = values.getOrDefault(def.id(), migratedValue == null ? def.defaultValue() : migratedValue);
        String initial = validatedOrDefault(def, raw);
        definitions.put(def.id(), def); active.put(def.id(), initial);
        // Freeze legacy imports into the new file at the first successful save. Unknown keys survive.
        values.put(def.id(), initial);
        return () -> value(def.id()).orElseThrow();
    }

    private static String validatedOrDefault(ConfigDefinition def, String raw) {
        try { return def.validate(raw); }
        catch (IllegalArgumentException e) {
            System.err.println("[BCCE/Config] Invalid value for " + def.id() + "; using schema default: " + e.getMessage());
            return def.defaultValue();
        }
    }

    /** Call after registration; old files are never modified. */
    public synchronized void persistDefaults() throws IOException {
        for (Scope scope : Scope.values()) {
            checkReadable(scope);
            write(file(scope), saved.get(scope));
        }
    }

    /** The first load of a save inherits the installation's migrated defaults. */
    public synchronized void openWorld(Path root) throws IOException {
        if (worldFile != null) throw new IllegalStateException("A configuration world is already open");
        checkReadable(Scope.SERVER);
        Path nextFile = root.resolve("buildcraft").resolve("configuration.properties");
        Map<String, String> defaults = new LinkedHashMap<>(saved.get(Scope.SERVER));
        Map<String, String> next = new LinkedHashMap<>(defaults);
        next.putAll(read(nextFile));
        for (ConfigDefinition def : definitions.values()) {
            if (def.scope() == Scope.SERVER) next.put(def.id(), validatedOrDefault(def, next.getOrDefault(def.id(), def.defaultValue())));
        }
        write(nextFile, next);
        serverDefaults = defaults; worldFile = nextFile; saved.put(Scope.SERVER, next);
        applyScope(Scope.SERVER, next);
        bump(Scope.SERVER);
    }

    /** All integrated worlds share this installation's personal gameplay profile. */
    public synchronized void openLocalWorld() throws IOException {
        if (worldFile != null) throw new IllegalStateException("A configuration world is already open");
        checkReadable(Scope.SERVER);
        worldFile = globalFile(Scope.SERVER); localWorld = true;
        applyScope(Scope.SERVER, saved.get(Scope.SERVER));
        bump(Scope.SERVER);
    }

    public synchronized void closeWorld() {
        if (worldFile == null) return;
        if (!localWorld) saved.put(Scope.SERVER, serverDefaults);
        applyScope(Scope.SERVER, saved.get(Scope.SERVER));
        worldFile = null; serverDefaults = null; localWorld = false; bump(Scope.SERVER);
    }

    public synchronized boolean hasWorld() { return worldFile != null; }
    private void applyScope(Scope scope, Map<String, String> source) {
        for (ConfigDefinition def : definitions.values()) if (def.scope() == scope)
            active.put(def.id(), source.getOrDefault(def.id(), def.defaultValue()));
    }
    private void bump(Scope scope) { revisions.put(scope, revisions.get(scope) + 1); }

    public record Snapshot(long revision, Map<String, String> values, Map<String, String> effective) {
        public Snapshot { values = Map.copyOf(values); effective = Map.copyOf(effective); }
    }
    public synchronized Snapshot snapshot(Scope scope) {
        Map<String, String> values = new LinkedHashMap<>(), effective = new LinkedHashMap<>();
        for (ConfigDefinition def : definitions.values()) if (def.scope() == scope) {
            values.put(def.id(), saved.get(scope).getOrDefault(def.id(), def.defaultValue()));
            effective.put(def.id(), active.get(def.id()));
        }
        return new Snapshot(revisions.get(scope), values, effective);
    }

    /** Trusted local caller or an already-authorized server packet handler only. */
    public synchronized Snapshot commit(Scope scope, long expectedRevision, Map<String, String> changes) throws IOException {
        if (scope == Scope.SERVER && worldFile == null) throw new IllegalStateException("No active world");
        return commitValues(scope, expectedRevision, changes);
    }

    /** Offline editor only; never use this entry point for incoming network requests. */
    public synchronized Snapshot commitLocalDefaults(long expectedRevision, Map<String, String> changes) throws IOException {
        if (worldFile != null) throw new IllegalStateException("A world is active");
        return commitValues(Scope.SERVER, expectedRevision, changes);
    }

    private Snapshot commitValues(Scope scope, long expectedRevision, Map<String, String> changes) throws IOException {
        checkReadable(scope);
        if (expectedRevision != revisions.get(scope)) throw new IllegalArgumentException("Configuration changed; reopen the editor");
        if (changes.size() > 2048) throw new IllegalArgumentException("Too many changes");
        Map<String, String> next = new LinkedHashMap<>(saved.get(scope));
        for (var entry : changes.entrySet()) {
            ConfigDefinition def = definitions.get(entry.getKey());
            if (def == null || def.scope() != scope) throw new IllegalArgumentException("Unknown or inaccessible setting: " + entry.getKey());
            next.put(def.id(), def.validate(entry.getValue()));
        }
        if (changes.isEmpty()) return snapshot(scope);
        write(file(scope), next);
        saved.put(scope, next);
        for (String id : changes.keySet()) if (definitions.get(id).applyPolicy() == ConfigDefinition.ApplyPolicy.LIVE)
            active.put(id, next.get(id));
        bump(scope);
        return snapshot(scope);
    }

    private void checkReadable(Scope scope) throws IOException {
        if (readErrors.containsKey(scope)) throw new IOException("Cannot read existing configuration; repair the file before saving", readErrors.get(scope));
    }
    private static Map<String, String> read(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(path)) return values;
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid configuration: " + path, e); }
        for (String key : p.stringPropertyNames()) values.put(key, p.getProperty(key));
        return values;
    }
    private static void write(Path path, Map<String, String> values) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "bc-config-", ".tmp");
        try {
            Properties p = new Properties(); p.putAll(values);
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "BuildCraft Community Edition configuration"); }
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
