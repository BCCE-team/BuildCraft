/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.config;

import buildcraft.lib.internal.debug.BCLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric native binding for BCCE's BCConfigSpec. Preserves the same qualified
 * keys and live Supplier getters as the NeoForge binding.
 *
 * Values are persisted to <modid>-common.toml. This reader intentionally
 * supports the primitive subset used by BCConfigSpec: booleans, integers,
 * decimals, strings and enums. It leaves unknown keys in an existing file
 * untouched. Malformed/out-of-range entries fall back to schema defaults.
 *
 * For new Fabric modules use bind(modId, schema) and listen(modId,...).
 * The no-argument bind(schema) cannot infer the originating module on Fabric
 * and fails explicitly instead of silently mixing multiple module configs.
 */
public final class ConfigBinding {
    private static final Map<BCConfigSpec, ConfigFile> BY_SCHEMA = new IdentityHashMap<>();
    private static final Map<String, ConfigFile> BY_MODULE = new LinkedHashMap<>();
    private static final Map<String, List<Listener>> LISTENERS = new LinkedHashMap<>();
    private static boolean watching;
    private static long serverTicks;

    private ConfigBinding() {}

    public static synchronized ConfigFile bind(BCConfigSpec schema) {
        throw new IllegalArgumentException("Fabric config requires a module ID: bind(modId, schema)");
    }

    public static synchronized ConfigFile bind(String modId, BCConfigSpec schema) {
        Objects.requireNonNull(schema, "schema");
        validateModId(modId);
        ConfigFile existing = BY_SCHEMA.get(schema);
        if (existing != null) {
            if (!existing.modId.equals(modId)) {
                throw new IllegalStateException("Config already bound for " + existing.modId);
            }
            return existing;
        }
        if (BY_MODULE.containsKey(modId)) {
            throw new IllegalStateException("Duplicate BCConfigSpec for " + modId);
        }

        ConfigFile file = new ConfigFile(modId,
            FabricLoader.getInstance().getConfigDir().resolve(modId + "-common.toml"));
        schema.bind(file.builder);
        BY_SCHEMA.put(schema, file);
        BY_MODULE.put(modId, file);
        file.load(false);
        for (Listener listener : LISTENERS.getOrDefault(modId, List.of())) {
            listener.onLoad.accept(modId);
        }
        return file;
    }

    public static synchronized void listen(String modId, Consumer<String> onLoad,
                                           Consumer<String> onReload) {
        validateModId(modId);
        Objects.requireNonNull(onLoad, "onLoad");
        Objects.requireNonNull(onReload, "onReload");
        LISTENERS.computeIfAbsent(modId, ignored -> new ArrayList<>())
            .add(new Listener(onLoad, onReload));
        if (BY_MODULE.containsKey(modId)) onLoad.accept(modId);
    }

    public static synchronized void reload(String modId) {
        ConfigFile file = BY_MODULE.get(modId);
        if (file != null) file.load(true);
    }

    public static synchronized void installWatch() {
        if (watching) return;
        watching = true;
        // Same reload lifecycle as onLoad/onReload, executed on the server
        // thread. No background file watcher reads a live config concurrently
        // with a machine tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++serverTicks % 100 != 0) return;
            synchronized (ConfigBinding.class) {
                for (ConfigFile file : BY_MODULE.values()) file.poll();
            }
        });
    }

    private static void validateModId(String modId) {
        if (modId == null || !modId.matches("[a-z0-9_\\-]+")) {
            throw new IllegalArgumentException("Invalid Fabric config mod ID: " + modId);
        }
    }

    private record Listener(Consumer<String> onLoad, Consumer<String> onReload) {}

    private static final class Entry<T> {
        final String name;
        final T fallback;
        final Function<String, T> parse;
        final Function<T, String> print;
        final List<String> comments;
        final boolean restartRequired;
        volatile T current;

        Entry(String name, T fallback, Function<String, T> parse,
              Function<T, String> print, List<String> comments, boolean restartRequired) {
            this.name = name;
            this.fallback = fallback;
            this.parse = parse;
            this.print = print;
            this.comments = comments;
            this.restartRequired = restartRequired;
            this.current = fallback;
        }

        void update(String raw, String modId, boolean reload) {
            // NeoForge worldRestart values remain effective until the next world
            // start, even if the config file changes while machines are ticking.
            if (reload && restartRequired) return;
            if (raw == null) { current = fallback; return; }
            try {
                current = Objects.requireNonNull(parse.apply(raw));
            } catch (RuntimeException failure) {
                current = fallback;
                BCLog.logger.warn("[lib.config] Invalid {} in {}: using schema default", name, modId);
            }
        }

        String defaultToml() { return print.apply(fallback); }
    }

    public static final class ConfigFile {
        private final String modId;
        private final Path path;
        private final Map<String, Entry<?>> entries = new LinkedHashMap<>();
        private final Builder builder = new Builder();
        private FileTime timestamp;

        private ConfigFile(String modId, Path path) {
            this.modId = modId;
            this.path = path;
        }

        public String modId() { return modId; }
        public Path path() { return path; }

        private void poll() {
            try {
                if (Files.isRegularFile(path)) {
                    FileTime now = Files.getLastModifiedTime(path);
                    if (timestamp == null || !timestamp.equals(now)) load(true);
                }
            } catch (IOException failure) {
                BCLog.logger.warn("[lib.config] Cannot check {}", path, failure);
            }
        }

        private synchronized void load(boolean reload) {
            try {
                Files.createDirectories(path.getParent());
                if (!Files.exists(path)) {
                    Files.writeString(path, defaultsToml(), StandardCharsets.UTF_8);
                }
                Map<String, String> input = parseToml(Files.readAllLines(path, StandardCharsets.UTF_8));
                for (Entry<?> entry : entries.values()) entry.update(input.get(entry.name), modId, reload);
                timestamp = Files.getLastModifiedTime(path);
                if (reload) for (Listener listener : LISTENERS.getOrDefault(modId, List.of())) {
                    listener.onReload.accept(modId);
                }
            } catch (IOException failure) {
                BCLog.logger.warn("[lib.config] Failed to load {}", path, failure);
                // Preserve live runtime values after an interrupted reload.
                if (!reload) {
                    for (Entry<?> entry : entries.values()) entry.update(null, modId, false);
                }
            }
        }

        private String defaultsToml() {
            StringBuilder out = new StringBuilder();
            out.append("# BuildCraft Fabric config for ").append(modId).append('\n');
            out.append("# Same keys and defaults as BCCE NeoForge 26.3.").append("\n\n");

            // TOML cannot switch back to the global section after [a.table].
            // Emit root keys first and then group dotted paths into sections.
            Map<String, List<Entry<?>>> sections = new LinkedHashMap<>();
            sections.put("", new ArrayList<>());
            for (Entry<?> entry : entries.values()) {
                int dot = entry.name.lastIndexOf('.');
                String section = dot < 0 ? "" : entry.name.substring(0, dot);
                sections.computeIfAbsent(section, unused -> new ArrayList<>()).add(entry);
            }
            for (var section : sections.entrySet()) {
                if (!section.getKey().isEmpty()) {
                    out.append('[').append(section.getKey()).append("]\n");
                }
                for (Entry<?> entry : section.getValue()) {
                    int dot = entry.name.lastIndexOf('.');
                    for (String comment : entry.comments) {
                        out.append("# ").append(comment).append('\n');
                    }
                    if (entry.restartRequired) out.append("# Requires world restart\n");
                    out.append(dot < 0 ? entry.name : entry.name.substring(dot + 1))
                        .append(" = ").append(entry.defaultToml()).append("\n\n");
                }
            }
            return out.toString();
        }

        private final class Builder implements BCConfigSpec.Backend {
            private final Deque<String> section = new ArrayDeque<>();
            private List<String> pendingComments = List.of();
            private boolean restart;

            // NeoForge BCConfigSpec permits dotted names inside pushed groups,
            // e.g. push("general") + define("pipes.mjPerMillibucket").
            // Treat each dot as a TOML path separator without changing the key.
            private static boolean validPath(String path) {
                return path != null && path.matches("[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*");
            }

            private String key(String name) {
                if (!validPath(name))
                    throw new IllegalArgumentException("Invalid BCConfigSpec key: " + name);
                return section.isEmpty() ? name : String.join(".", section) + "." + name;
            }

            private <T> Supplier<T> add(String name, T fallback, Function<String, T> parser,
                                        Function<T, String> printer) {
                String full = key(name);
                if (entries.containsKey(full)) {
                    throw new IllegalArgumentException("Duplicate BCConfigSpec key: " + full);
                }
                Entry<T> entry = new Entry<>(full, fallback, parser, printer,
                    pendingComments, restart);
                entries.put(full, entry);
                pendingComments = List.of();
                restart = false;
                return () -> entry.current;
            }

            public void push(String path) {
                if (!validPath(path))
                    throw new IllegalArgumentException("Invalid BCConfigSpec section: " + path);
                section.addLast(path);
            }
            public void pop() {
                if (section.isEmpty()) throw new IllegalStateException("Config pop without push");
                section.removeLast();
            }
            public void comment(String... lines) {
                pendingComments = List.of(lines.clone());
            }
            public void worldRestart() { restart = true; }

            public Supplier<Boolean> bool(String name, boolean value) {
                return add(name, value, raw -> {
                    if (!"true".equals(raw) && !"false".equals(raw)) throw new IllegalArgumentException();
                    return Boolean.parseBoolean(raw);
                }, String::valueOf);
            }

            public Supplier<Integer> integer(String name, int value, int min, int max) {
                return add(name, value, raw -> {
                    int parsed = Integer.parseInt(raw.replace("_", ""));
                    if (parsed < min || parsed > max) throw new IllegalArgumentException();
                    return parsed;
                }, String::valueOf);
            }

            public Supplier<Double> decimal(String name, double value, double min, double max) {
                return add(name, value, raw -> {
                    double parsed = Double.parseDouble(raw.replace("_", ""));
                    if (!Double.isFinite(parsed) || parsed < min || parsed > max) throw new IllegalArgumentException();
                    return parsed;
                }, String::valueOf);
            }

            public Supplier<String> string(String name, String value) {
                return add(name, value, FabricTomlValues::readString, FabricTomlValues::writeString);
            }

            public <E extends Enum<E>> Supplier<E> enumeration(String name, E value, E[] allowed) {
                Set<E> accepted = allowed == null || allowed.length == 0
                    ? Set.of(value.getDeclaringClass().getEnumConstants())
                    : Set.of(allowed);
                return add(name, value, raw -> {
                    String literal = FabricTomlValues.readString(raw);
                    E parsed = Enum.valueOf(value.getDeclaringClass(), literal);
                    if (!accepted.contains(parsed)) throw new IllegalArgumentException();
                    return parsed;
                }, v -> FabricTomlValues.writeString(v.name()));
            }
        }
    }

    /** Primitive TOML subset, separated for independent no-loader unit testing. */
    static final class FabricTomlValues {
        private FabricTomlValues() {}

        static Map<String, String> parseToml(List<String> lines) {
            Map<String, String> result = new LinkedHashMap<>();
            String section = "";
            for (String line : lines) {
                String cleaned = stripComment(line).trim();
                if (cleaned.isEmpty()) continue;
                if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                    section = cleaned.substring(1, cleaned.length()-1).trim();
                    continue;
                }
                int equals = cleaned.indexOf('=');
                if (equals < 1) continue;
                String key = cleaned.substring(0, equals).trim();
                String value = cleaned.substring(equals + 1).trim();
                if (key.isBlank()) continue;
                result.put(section.isEmpty() ? key : section + "." + key, value);
            }
            return result;
        }

        private static String stripComment(String line) {
            boolean quoted = false, escaped = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (c == '\\' && quoted) { escaped = true; continue; }
                if (c == '"') { quoted = !quoted; continue; }
                if (c == '#' && !quoted) return line.substring(0, i);
            }
            return line;
        }

        static String readString(String value) {
            if (value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
                throw new IllegalArgumentException("Expected quoted TOML string");
            }
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (escaped) {
                    out.append(switch (c) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '"', '\\' -> c;
                        default -> throw new IllegalArgumentException("Unknown TOML escape " + c);
                    });
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else {
                    out.append(c);
                }
            }
            if (escaped) throw new IllegalArgumentException("Dangling TOML escape");
            return out.toString();
        }

        static String writeString(String value) {
            return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
    }

    private static Map<String, String> parseToml(List<String> lines) {
        return FabricTomlValues.parseToml(lines);
    }
}
