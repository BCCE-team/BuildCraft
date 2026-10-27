package buildcraft.lib.balance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure-Java, server-authoritative balance INI store. No Minecraft or loader classes here. */
public final class BalanceIni {
    public enum FileKind { MACHINES("buildcraft-machines.ini"), ROBOTS("buildcraft-robots.ini");
        public final String name;
        FileKind(String name) { this.name = name; }
    }
    public record Parameter(FileKind file, String section, String key, String defaultValue,
                            double minimum, double maximum, boolean integral) {
        public String path() { return section + "." + key; }
        public String validate(String raw) {
            try {
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value) || value < minimum || value > maximum
                        || (integral && value != Math.rint(value))) {
                    throw new IllegalArgumentException("out of range [" + minimum + ", " + maximum + "]");
                }
                return integral ? Long.toString((long) value) : Double.toString(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: " + raw, e);
            }
        }
    }
    private static final int SCHEMA_VERSION = 1;
    /** Every balance file has an independent opt-in. */
    private static final String USE_CONFIG_KEY = "meta/use_config";
    /** 8.0.26 shared switch; only read during one-time migration. */
    private static final String LEGACY_MODPACK_MODE_KEY = "meta/modpack_mode";
    private static final String[] MACHINES = {
        "buildcraftfactory:pump", "buildcraftfactory:distiller", "buildcraftfactory:heat_exchanger",
        "buildcraftfactory:mining_well", "buildcraftbuilders:quarry", "buildcraftbuilders:builder",
        "buildcraftbuilders:filler", "buildcraftfactory:flood_gate", "buildcraftsilicon:assembly_table",
        "buildcraftsilicon:integration_table", "buildcraftsilicon:programming_table",
        "buildcraftsilicon:advanced_crafting_table", "buildcraftfactory:auto_workbench"
    };
    private static final String[] ROBOTS = {
        "picker", "carrier", "fluid_carrier", "lumberjack", "harvester", "miner",
        "planter", "farmer", "leave_cutter", "butcher", "shovelman", "pump",
        "delivery", "knight", "bomber", "stripes", "builder"
    };
    private static final List<Parameter> PARAMETERS = buildSchema();
    private static List<Parameter> buildSchema() {
        List<Parameter> p = new ArrayList<>();
        for (String machine : MACHINES) {
            String s = "machine." + machine;
            p.add(decimal(FileKind.MACHINES, s, "energy_cost_multiplier", 1.0, 0.01, 1000));
            p.add(decimal(FileKind.MACHINES, s, "work_speed_multiplier", 1.0, 0.01, 100));
        }
        String pump = "machine.buildcraftfactory:pump";
        p.add(integer(FileKind.MACHINES, pump, "energy_per_bucket_mj", 10, 1, 100000));
        p.add(integer(FileKind.MACHINES, pump, "tank_capacity_mb", 16000, 1000, 1000000));
        p.add(integer(FileKind.MACHINES, pump, "max_distance_blocks", 64, 16, 128));
        p.add(integer(FileKind.MACHINES, pump, "consume_water_sources", 0, 0, 1));
        String quarry = "machine.buildcraftbuilders:quarry";
        p.add(integer(FileKind.MACHINES, quarry, "frame_min_height", 4, 1, 512));
        p.add(integer(FileKind.MACHINES, quarry, "max_tasks_per_tick", 4, 1, 20));
        p.add(integer(FileKind.MACHINES, quarry, "power_divisor", 2, 0, 100));
        p.add(decimal(FileKind.MACHINES, quarry, "max_frame_speed", 0, 0, 5120));
        p.add(decimal(FileKind.MACHINES, quarry, "max_mine_rate", 0, 0, 1000));
        p.add(decimal(FileKind.MACHINES, "machine.mining", "energy_multiplier", 1.0, 1, 200));
        p.add(integer(FileKind.MACHINES, "machine.mining", "max_depth", 256, 32, 4096));
        p.add(integer(FileKind.MACHINES, "machine.markers", "max_distance_blocks", 64, 16, 256));
        for (String name : ROBOTS) {
            String section = "robot." + name;
            p.add(decimal(FileKind.ROBOTS, section, "movement_speed_multiplier", 1, 0.1, 10));
            p.add(decimal(FileKind.ROBOTS, section, "energy_cost_multiplier", 1, 0.01, 100));
        }
        return List.copyOf(p);
    }
    private static Parameter integer(FileKind kind, String section, String key, long d, long min, long max) {
        return new Parameter(kind, section, key, Long.toString(d), min, max, true);
    }
    private static Parameter decimal(FileKind kind, String section, String key, double d, double min, double max) {
        return new Parameter(kind, section, key, Double.toString(d), min, max, false);
    }
    public static List<Parameter> parameters() { return PARAMETERS; }

    private final Path directory;
    private volatile Map<String, String> effective = Map.of();
    private volatile Map<FileKind, Boolean> enabledFiles = Map.of(
        FileKind.MACHINES, false, FileKind.ROBOTS, false);
    private final List<String> warnings = new ArrayList<>();

    public BalanceIni(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }
    private static String path(FileKind k, String section, String key) { return k.name + "/" + section + "/" + key; }
    private Path file(FileKind k) { return directory.resolve(k.name); }

    public synchronized void load() throws IOException {
        Files.createDirectories(directory);
        Map<FileKind, Map<String, String>> rawFiles = new EnumMap<>(FileKind.class);
        for (FileKind kind : FileKind.values()) {
            if (!Files.exists(file(kind))) {
                writeDefaults(kind, false);
                if (kind == FileKind.MACHINES) importLegacyMachineValues();
            }
            Map<String, String> raw = parse(file(kind));
            String schema = raw.get("meta/schema_version");
            if (!Integer.toString(SCHEMA_VERSION).equals(schema)) {
                throw new IOException("Unsupported schema_version in " + kind.name + ": " + schema);
            }
            rawFiles.put(kind, raw);
        }

        // Preserve existing 8.0.26 modpack_mode=true installations: a shared
        // legacy switch becomes two independent use_config=true switches ONCE.
        // Explicit new switches always win; a missing new switch otherwise
        // defaults to false and does not follow the other file at runtime.
        Map<String, String> oldMachines = rawFiles.get(FileKind.MACHINES);
        boolean migrateShared = !oldMachines.containsKey(USE_CONFIG_KEY)
            && !rawFiles.get(FileKind.ROBOTS).containsKey(USE_CONFIG_KEY)
            && oldMachines.containsKey(LEGACY_MODPACK_MODE_KEY);
        Map<FileKind, Boolean> nextModes = new EnumMap<>(FileKind.class);
        Map<String, String> nextValues = new LinkedHashMap<>();
        List<String> nextWarnings = new ArrayList<>();
        for (FileKind kind : FileKind.values()) {
            Map<String, String> raw = rawFiles.get(kind);
            String flag = raw.get(USE_CONFIG_KEY);
            if (flag == null) {
                flag = migrateShared ? oldMachines.get(LEGACY_MODPACK_MODE_KEY) : "false";
            }
            if (!"true".equalsIgnoreCase(flag) && !"false".equalsIgnoreCase(flag)) {
                throw new IOException("Invalid use_config in " + kind.name + ": " + flag
                    + " (expected true or false)");
            }
            boolean enabled = Boolean.parseBoolean(flag);
            nextModes.put(kind, enabled);
            // bcce_version is obsolete; modpack_mode is accepted ONLY for
            // migration and has no effect once use_config exists.
            Set<String> known = new LinkedHashSet<>(Set.of(
                "meta/schema_version", "meta/bcce_version", USE_CONFIG_KEY));
            if (kind == FileKind.MACHINES) known.add(LEGACY_MODPACK_MODE_KEY);
            for (Parameter param : PARAMETERS) {
                if (param.file != kind) continue;
                String local = param.section + "/" + param.key;
                known.add(local);
                // Disabled files do not supply gameplay values. Even invalid
                // numeric edits there must not prevent the other INI working.
                String value = enabled ? raw.get(local) : null;
                if (value == null) value = param.defaultValue;
                try { nextValues.put(path(kind, param.section, param.key), param.validate(value)); }
                catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid " + kind.name + " [" + param.section + "] " + param.key
                        + " = " + value + " (" + ex.getMessage() + ")", ex);
                }
            }
            for (String key : raw.keySet()) {
                if (!known.contains(key)) nextWarnings.add("Unrecognized setting " + kind.name + ": " + key);
            }
        }

        // Migrate the old shared flag and add missing independent flags without
        // rewriting any other user settings. Do not touch files if validation
        // failed. Backups are made before the one-time metadata update.
        for (FileKind kind : FileKind.values()) {
            Map<String, String> raw = rawFiles.get(kind);
            if (!raw.containsKey(USE_CONFIG_KEY)
                    || (kind == FileKind.MACHINES && raw.containsKey(LEGACY_MODPACK_MODE_KEY))) {
                migrateSwitch(kind, nextModes.get(kind), raw.containsKey(USE_CONFIG_KEY));
            }
        }
        // All-or-nothing publication of the live gameplay snapshot.
        effective = Collections.unmodifiableMap(new LinkedHashMap<>(nextValues));
        enabledFiles = Collections.unmodifiableMap(new EnumMap<>(nextModes));
        warnings.clear(); warnings.addAll(nextWarnings);
    }

    /** One-time migration of old native config balance keys. Native config is never rewritten. */
    private void importLegacyMachineValues() throws IOException {
        Map<String, String> migrated = new LinkedHashMap<>();
        collectLegacy(directory.resolve("buildcraftbuilders-common.toml"), Map.of(
            "quarryFrameMinHeight", "machine.buildcraftbuilders:quarry/frame_min_height",
            "quarryMaxTasksPerTick", "machine.buildcraftbuilders:quarry/max_tasks_per_tick",
            "quarryPowerDivisor", "machine.buildcraftbuilders:quarry/power_divisor",
            "quarryMaxFrameSpeed", "machine.buildcraftbuilders:quarry/max_frame_speed",
            "quarryMaxBlockMineRate", "machine.buildcraftbuilders:quarry/max_mine_rate"
        ), migrated);
        collectLegacy(directory.resolve("buildcraftcore-common.toml"), Map.of(
            "pumpsConsumeWater", "machine.buildcraftfactory:pump/consume_water_sources",
            "pumpMaxDistance", "machine.buildcraftfactory:pump/max_distance_blocks",
            "markerMaxDistance", "machine.markers/max_distance_blocks",
            "miningMultiplier", "machine.mining/energy_multiplier",
            "miningMaxDepth", "machine.mining/max_depth"
        ), migrated);
        if (migrated.isEmpty()) return;
        Path target = file(FileKind.MACHINES);
        String text = Files.readString(target, StandardCharsets.UTF_8);
        for (Parameter p : PARAMETERS) {
            if (p.file != FileKind.MACHINES) continue;
            String value = migrated.get(p.section + "/" + p.key);
            if (value == null) continue;
            String normalized;
            try { normalized = p.validate(value); }
            catch (IllegalArgumentException e) {
                System.err.println("[BCCE/Balance] Skipping invalid legacy value " + p.path() + "=" + value);
                continue;
            }
            // Use the complete section to avoid confusing similarly named keys in different machines.
            String sectionHead = "[" + p.section + "]\n";
            int start = text.indexOf(sectionHead);
            if (start < 0) continue;
            int end = text.indexOf("\n[", start + sectionHead.length());
            if (end < 0) end = text.length();
            String part = text.substring(start, end);
            String expected = p.key + " = " + p.defaultValue;
            part = part.replace(expected, p.key + " = " + normalized);
            text = text.substring(0, start) + part + text.substring(end);
            System.err.println("[BCCE/Balance] Migrated legacy setting " + p.path() + " = " + normalized);
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }
    private static void collectLegacy(Path path, Map<String, String> names, Map<String, String> out) throws IOException {
        if (!Files.isRegularFile(path)) return;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String str = line.trim();
            if (str.startsWith("#") || str.startsWith("[") || str.isEmpty()) continue;
            int equals = str.indexOf('=');
            if (equals <= 0) continue;
            String target = names.get(str.substring(0, equals).trim());
            if (target != null) {
                String raw = str.substring(equals + 1).trim();
                if (raw.equalsIgnoreCase("true")) raw = "1";
                if (raw.equalsIgnoreCase("false")) raw = "0";
                out.put(target, raw);
            }
        }
    }

    private static Map<String, String> parse(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        String section = "";
        int lineNum = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNum++;
            String s = line.trim();
            if (s.isEmpty() || s.startsWith(";") || s.startsWith("#")) continue;
            if (s.startsWith("[") && s.endsWith("]")) {
                section = s.substring(1, s.length() - 1).trim();
                if (section.isEmpty()) throw new IOException("Empty section in " + file + ":" + lineNum);
                continue;
            }
            int eq = s.indexOf('=');
            if (eq < 1 || section.isEmpty()) throw new IOException("Malformed INI " + file + ":" + lineNum);
            String key = section + "/" + s.substring(0, eq).trim();
            String value = s.substring(eq + 1).trim();
            if (values.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate INI key " + key + " in " + file + ":" + lineNum);
            }
        }
        return values;
    }

    /** Change only [meta] during migration. Keep all existing balance edits. */
    private void migrateSwitch(FileKind kind, boolean enabled, boolean hasNewSwitch) throws IOException {
        Path target = file(kind);
        String original = Files.readString(target, StandardCharsets.UTF_8);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> updated = new ArrayList<>();
        boolean inMeta = false;
        boolean inserted = false;
        for (String line : original.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inMeta = "[meta]".equals(trimmed);
            }
            // Remove exactly the comments written by the previous generator.
            if (trimmed.equals("; With modpack_mode = false BCCE always uses its built-in balance values.")
                || trimmed.equals("; With modpack_mode = true BCCE applies the values in BOTH INI files.")
                || trimmed.equals("; Set modpack_mode in buildcraft-machines.ini only.")
                || trimmed.equals("; false = built-in BCCE defaults (INI balance values ignored)")
                || trimmed.equals("; true = use the machine and robot balance values from these INI files")) {
                continue;
            }
            if (inMeta && trimmed.matches("modpack_mode\\s*=.*")) continue;
            updated.add(line);
            if (inMeta && !hasNewSwitch && trimmed.matches("schema_version\\s*=.*")) {
                updated.add("; false = use built-in BCCE " + (kind == FileKind.MACHINES ? "machine" : "robot") + " balance");
                updated.add("; true = use the values from THIS file only");
                updated.add("use_config = " + enabled);
                inserted = true;
            }
        }
        if (!hasNewSwitch && !inserted) {
            throw new IOException("Missing [meta] schema_version while migrating " + kind.name);
        }
        String result = String.join(newline, updated);
        if (original.equals(result)) return;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Files.copy(target, target.resolveSibling(kind.name + ".before-use_config-" + stamp + ".bak"));
        writeAtomic(target, result);
    }

    private void writeDefaults(FileKind kind, boolean backup) throws IOException {
        Path target = file(kind);
        if (backup && Files.exists(target)) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Files.copy(target, target.resolveSibling(kind.name + "." + stamp + ".bak"));
        }
        String scope = kind == FileKind.MACHINES ? "machine" : "robot";
        StringBuilder out = new StringBuilder("; BuildCraft Community Edition " + scope + " balance\n"
            + "; Edit values and restart Minecraft/the server to apply.\n"
            + "; This file has its own independent use_config switch.\n"
            + "; Switching this file does NOT change the other balance file.\n\n"
            + "[meta]\n" + "schema_version = " + SCHEMA_VERSION + "\n"
            + "; false = built-in BCCE " + scope + " defaults (edits in THIS file ignored)\n"
            + "; true = use the " + scope + " values from THIS file only\n"
            + "use_config = false\n");
        Map<String, List<Parameter>> bySection = new LinkedHashMap<>();
        for (Parameter p : PARAMETERS) {
            if (p.file == kind) bySection.computeIfAbsent(p.section, ignored -> new ArrayList<>()).add(p);
        }
        for (var entry : bySection.entrySet()) {
            out.append("\n[").append(entry.getKey()).append("]\n");
            for (Parameter p : entry.getValue()) {
                out.append(p.key).append(" = ").append(p.defaultValue).append("\n");
            }
        }
        writeAtomic(target, out.toString());
    }

    private void writeAtomic(Path target, String text) throws IOException {
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public boolean useConfig(FileKind kind) {
        return Boolean.TRUE.equals(enabledFiles.get(Objects.requireNonNull(kind)));
    }
    public synchronized List<String> warnings() { return List.copyOf(warnings); }
    public double decimal(FileKind kind, String section, String key, double fallback) {
        if (!useConfig(kind)) return fallback;
        String str = effective.get(path(kind, section, key));
        return str == null ? fallback : Double.parseDouble(str);
    }
    public int integer(FileKind kind, String section, String key, int fallback) {
        return (int) decimal(kind, section, key, fallback);
    }
}
