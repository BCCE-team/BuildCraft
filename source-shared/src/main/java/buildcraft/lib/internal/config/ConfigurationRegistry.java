package buildcraft.lib.internal.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigSchema;
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
    private static final String SCHEMA_PREFIX = "@bcce.schema.";
    private final EnumMap<Scope, Map<String, ConfigSchema>> schemas = new EnumMap<>(Scope.class);
    private final Map<String, ConfigDefinition> definitions = new LinkedHashMap<>();
    private final EnumMap<Scope, Map<String, String>> saved = new EnumMap<>(Scope.class);
    private final Map<String, String> active = new HashMap<>();
    private final EnumMap<Scope, Long> revisions = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, IllegalStateException> schemaErrors = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, IOException> readErrors = new EnumMap<>(Scope.class);
    private Path worldFile;
    private Map<String, String> serverDefaults;
    private boolean localWorld;
    private boolean schemaFrozen;
    private boolean serverRestartInitialized;

    public ConfigurationRegistry(Path directory) {
        this.directory = directory;
        for (Scope scope : Scope.values()) {
            Map<String, String> values;
            try { values = read(globalFile(scope)); }
            catch (IOException e) { readErrors.put(scope, e); values = new LinkedHashMap<>(); }
            saved.put(scope, values); revisions.put(scope, 0L); schemas.put(scope, new LinkedHashMap<>());
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
        checkRegistrationOpen();
        // Client LIVE settings are read through their effective-value handle. Server hot reload needs
        // a separate migration contract for inventories and running machines.
        if (definition.scope() == Scope.SERVER && definition.applyPolicy() == ConfigDefinition.ApplyPolicy.LIVE)
            throw new IllegalArgumentException("Live server addon mutations are not supported yet");
        List<String> path=new ArrayList<>(); path.add(definition.ownerNamespace()); path.addAll(definition.category());
        ConfigDefinition grouped=new ConfigDefinition(definition.id(),definition.editorSection(),path,definition.scope(),definition.kind(),definition.defaultValue(),definition.minimum(),definition.maximum(),definition.choices(),definition.nameKey(),definition.descriptionKey(),definition.tooltipKey(),definition.fallbackName(),definition.fallbackDescription(),definition.fallbackTooltip(),definition.applyPolicy());
        return register(grouped, null);
    }

    @Override public synchronized void registerSchema(ConfigSchema schema) {
        Objects.requireNonNull(schema, "schema");
        checkRegistrationOpen();
        Scope scope = schema.scope();
        String namespace = schema.namespace();
        if (schemas.get(scope).containsKey(namespace)) throw new IllegalArgumentException("Duplicate configuration schema: " + namespace);
        if (definitions.values().stream().anyMatch(d -> d.scope() == scope && d.ownerNamespace().equals(namespace)))
            throw new IllegalStateException("Register the configuration schema before its definitions: " + namespace);
        if (readErrors.containsKey(scope)) throw new IllegalStateException("Cannot migrate unreadable configuration", readErrors.get(scope));
        // Publish only after every step succeeds on a private copy. No disk writes during registration.
        Map<String, String> next = new LinkedHashMap<>(saved.get(scope));
        migrate(next, schema);
        saved.put(scope, next);
        schemas.get(scope).put(namespace, schema);
        bump(scope);
    }

    private static void migrate(Map<String, String> store, ConfigSchema schema) {
        String prefix = schema.namespace() + ":";
        String marker = SCHEMA_PREFIX + schema.namespace();
        String rawVersion = store.get(marker);
        int version;
        try { version = rawVersion == null ? 0 : Integer.parseInt(rawVersion); }
        catch (NumberFormatException e) { throw new IllegalStateException("Invalid configuration schema version: " + schema.namespace(), e); }
        if (version < 0 || version > schema.version())
            throw new IllegalStateException("Unsupported saved configuration schema " + schema.namespace() + " version " + version + " (supported up to " + schema.version() + ")");
        if (rawVersion != null && version == schema.version()) return;
        Map<String, String> values = new LinkedHashMap<>();
        store.forEach((key, value) -> { if (key.startsWith(prefix)) values.put(key.substring(prefix.length()), value); });
        if (rawVersion != null || !values.isEmpty()) {
            for (int step = version; step < schema.version(); step++) {
                try { schema.migrations().get(step).accept(values); }
                catch (RuntimeException e) { throw new IllegalStateException("Configuration migration failed: " + schema.namespace() + " " + step + " -> " + (step + 1), e); }
            }
        }
        for (var entry : values.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key == null || !key.matches("[a-zA-Z0-9_./-]+") || prefix.length() + key.length() > 192
                    || value == null || value.length() > 4096)
                throw new IllegalStateException("Invalid migration output for configuration schema " + schema.namespace());
        }
        store.keySet().removeIf(key -> key.startsWith(prefix));
        values.forEach((key, value) -> store.put(prefix + key, value));
        store.put(marker, Integer.toString(schema.version()));
    }

    /** Do not mix versioned defaults and older world values for temporarily absent addons. */
    private void preserveAbsentSchemas(Map<String, String> defaults, Map<String, String> world) {
        Set<String> namespaces = new HashSet<>();
        for (String key : world.keySet()) {
            if (key.startsWith(SCHEMA_PREFIX)) namespaces.add(key.substring(SCHEMA_PREFIX.length()));
            else if (key.indexOf(':') > 0) namespaces.add(key.substring(0, key.indexOf(':')));
        }
        for (String namespace : namespaces) {
            String marker = SCHEMA_PREFIX + namespace;
            if (!schemas.get(Scope.SERVER).containsKey(namespace) && !Objects.equals(defaults.get(marker), world.get(marker))) {
                defaults.remove(marker);
                defaults.keySet().removeIf(key -> key.startsWith(namespace + ":"));
            }
        }
    }

    private String loadValue(ConfigDefinition def, String raw) {
        // Versioned data must be valid after migration; do not destroy it by silently using a default.
        if (schemas.get(def.scope()).containsKey(def.ownerNamespace())) {
            try { return def.validate(raw); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Invalid versioned configuration value: " + def.id(), e); }
        }
        return validatedOrDefault(def, raw);
    }

    /** Internal migration access to an unregistered legacy key; never exposes remote values. */
    public synchronized Optional<String> savedClientValue(String id) { return Optional.ofNullable(saved.get(Scope.CLIENT).get(id)); }

    /** Called at ApiLifecycle.FROZEN, after all built-in schemas and addons have registered. */
    public synchronized void freezeSchema() {
        if (!schemaErrors.isEmpty()) throw new IllegalStateException("Configuration schema validation failed", schemaErrors.values().iterator().next());
        schemaFrozen = true;
    }

    private void checkRegistrationOpen() {
        if (schemaFrozen || worldFile != null)
            throw new IllegalStateException("Configuration schema registration is closed");
    }

    public synchronized Supplier<String> register(ConfigDefinition def, String migratedValue) {
        checkRegistrationOpen();
        if (definitions.size()>=2048) throw new IllegalStateException("Too many configuration definitions");
        if (definitions.containsKey(def.id())) throw new IllegalArgumentException("Duplicate setting: " + def.id());
        Map<String, String> values = saved.get(def.scope());
        String raw = values.getOrDefault(def.id(), migratedValue == null ? def.defaultValue() : migratedValue);
        String initial;
        try { initial = loadValue(def, raw); }
        catch (IllegalStateException e) {
            // Even if an addon catches a bad migrated definition, do not persist the upgraded marker.
            schemaErrors.put(def.scope(), e); throw e;
        }
        definitions.put(def.id(), def); active.put(def.id(), initial);
        // Freeze legacy imports into the new file at the first successful save. Unknown keys survive.
        values.put(def.id(), initial);
        bump(def.scope());
        return () -> value(def.id()).orElseThrow();
    }

    private static String validatedOrDefault(ConfigDefinition def, String raw) {
        // ALL_TILES was indistinguishable from SELF_TILES: both only load requesting tiles.
        if (def.id().equals("buildcraftcore:general/chunkLoadLevel") && "ALL_TILES".equals(raw)) raw = "SELF_TILES";
        try { return def.validate(raw); }
        catch (IllegalArgumentException e) {
            System.err.println("[BCCE/Config] Invalid value for " + def.id() + "; using schema default: " + e.getMessage());
            return def.defaultValue();
        }
    }

    /** Call after registration; old files are never modified. */
    public synchronized void persistDefaults() throws IOException {
        for (Scope scope : Scope.values()) checkReadable(scope);
        for (Scope scope : Scope.values()) write(file(scope), saved.get(scope));
    }

    /** The first load of a save inherits the installation's migrated defaults. */
    public synchronized void openWorld(Path root) throws IOException {
        if (worldFile != null) throw new IllegalStateException("A configuration world is already open");
        checkReadable(Scope.SERVER);
        Path nextFile = root.resolve("buildcraft").resolve("configuration.properties");
        Map<String, String> defaults = new LinkedHashMap<>(saved.get(Scope.SERVER));
        Map<String, String> next = new LinkedHashMap<>(defaults);
        Map<String, String> world = read(nextFile);
        // Migrate the world in its own version before overlaying it on current defaults.
        for (ConfigSchema schema : schemas.get(Scope.SERVER).values()) migrate(world, schema);
        preserveAbsentSchemas(next, world);
        next.putAll(world);
        for (ConfigDefinition def : definitions.values()) {
            if (def.scope() == Scope.SERVER) next.put(def.id(), loadValue(def, next.getOrDefault(def.id(), def.defaultValue())));
        }
        write(nextFile, next);
        serverDefaults = defaults; worldFile = nextFile; saved.put(Scope.SERVER, next);
        // On a dedicated process the first authoritative world file supplies the
        // initial RESTART values. Later world/session changes cannot activate them.
        applyScope(Scope.SERVER, next, !serverRestartInitialized);
        serverRestartInitialized = true;
        bump(Scope.SERVER);
    }

    /** All integrated worlds share this installation's personal gameplay profile. */
    public synchronized void openLocalWorld() throws IOException {
        if (worldFile != null) throw new IllegalStateException("A configuration world is already open");
        checkReadable(Scope.SERVER);
        worldFile = globalFile(Scope.SERVER); localWorld = true;
        serverRestartInitialized = true;
        applyScope(Scope.SERVER, saved.get(Scope.SERVER), false);
        bump(Scope.SERVER);
    }

    public synchronized void closeWorld() {
        if (worldFile == null) return;
        if (!localWorld) saved.put(Scope.SERVER, serverDefaults);
        applyScope(Scope.SERVER, saved.get(Scope.SERVER), false);
        worldFile = null; serverDefaults = null; localWorld = false; bump(Scope.SERVER);
    }

    public synchronized boolean hasWorld() { return worldFile != null; }
    private void applyScope(Scope scope, Map<String, String> source, boolean includeRestart) {
        for (ConfigDefinition def : definitions.values()) if (def.scope() == scope
                && (includeRestart || def.applyPolicy() != ConfigDefinition.ApplyPolicy.RESTART))
            active.put(def.id(), source.getOrDefault(def.id(), def.defaultValue()));
    }

    /** Client play-session login, not dimension changes or server-side world lifecycle. */
    public synchronized void openClientSession() {
        applyScope(Scope.CLIENT, saved.get(Scope.CLIENT), false);
        bump(Scope.CLIENT);
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
        if (schemaErrors.containsKey(scope)) throw new IOException("Cannot save a configuration with a failed schema validation", schemaErrors.get(scope));
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
