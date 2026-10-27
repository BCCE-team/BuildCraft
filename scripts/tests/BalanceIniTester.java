import buildcraft.lib.balance.BalanceIni;
import buildcraft.lib.balance.BalanceIni.FileKind;
import java.nio.file.Files;
import java.nio.file.Path;

public class BalanceIniTester {
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String read(Path p) throws Exception { return Files.readString(p); }
    private static void edit(Path p, String oldValue, String newValue) throws Exception {
        String source = read(p);
        expect(source.contains(oldValue), "Missing source string in " + p + ": " + oldValue);
        Files.writeString(p, source.replace(oldValue, newValue));
    }
    private static void expectMode(BalanceIni ini, boolean machines, boolean robots) {
        expect(ini.useConfig(FileKind.MACHINES) == machines, "Wrong machine use_config");
        expect(ini.useConfig(FileKind.ROBOTS) == robots, "Wrong robot use_config");
        expect(ini.integer(FileKind.MACHINES, "machine.buildcraftfactory:pump", "energy_per_bucket_mj", 10)
            == (machines ? 55 : 10), "Wrong pump energy value");
        expect(ini.decimal(FileKind.ROBOTS, "robot.picker", "movement_speed_multiplier", 1.0)
            == (robots ? 3.0 : 1.0), "Wrong robot speed value");
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("bcce-use-config-");
        Path machines = dir.resolve(FileKind.MACHINES.name);
        Path robots = dir.resolve(FileKind.ROBOTS.name);
        BalanceIni ini = new BalanceIni(dir);
        ini.load();
        expect(Files.exists(machines) && Files.exists(robots), "Both INIs must be generated");
        for (Path path : new Path[] { machines, robots }) {
            String generated = read(path);
            expect(generated.contains("schema_version = 1"), "Missing schema marker");
            expect(generated.contains("use_config = false"), "Missing independent opt-in");
            expect(generated.contains("THIS file"), "Missing independent-mode comment");
            expect(!generated.contains("bcce_version ="), "Generated INI must not contain BCCE version");
            expect(!generated.contains("modpack_mode"), "Generated INI must not contain old switch");
        }
        edit(machines, "energy_per_bucket_mj = 10", "energy_per_bucket_mj = 55");
        edit(robots, "movement_speed_multiplier = 1.0", "movement_speed_multiplier = 3.0");
        ini.load();
        expectMode(ini, false, false);

        // All four combinations must be independent.
        edit(robots, "use_config = false", "use_config = true");
        ini.load();
        expectMode(ini, false, true);
        edit(machines, "use_config = false", "use_config = true");
        ini.load();
        expectMode(ini, true, true);
        edit(robots, "use_config = true", "use_config = false");
        ini.load();
        expectMode(ini, true, false);
        edit(machines, "use_config = true", "use_config = false");
        ini.load();
        expectMode(ini, false, false);

        // Inactive file data is ignored, even if its numeric values were edited incorrectly.
        edit(machines, "energy_per_bucket_mj = 55", "energy_per_bucket_mj = -5");
        edit(robots, "use_config = false", "use_config = true");
        ini.load();
        expect(ini.useConfig(FileKind.ROBOTS), "Inactive machine config must not block robot settings");
        expect(ini.integer(FileKind.MACHINES, "machine.buildcraftfactory:pump", "energy_per_bucket_mj", 10) == 10,
            "Disabled machine INI must not expose malformed value");
        edit(machines, "use_config = false", "use_config = true");
        boolean rejected = false;
        try { ini.load(); } catch (java.io.IOException e) { rejected = true; }
        expect(rejected, "Invalid enabled machine value must fail validation");
        expect(!ini.useConfig(FileKind.MACHINES) && ini.useConfig(FileKind.ROBOTS),
            "Invalid enabled INI must not partially publish new flags");
        edit(machines, "energy_per_bucket_mj = -5", "energy_per_bucket_mj = 55");
        ini.load();
        expectMode(ini, true, true);

        // Invalid robot switch must reject the update, retaining the previous snapshot.
        edit(robots, "use_config = true", "use_config = perhaps");
        rejected = false;
        try { ini.load(); } catch (java.io.IOException e) { rejected = true; }
        expect(rejected && ini.useConfig(FileKind.ROBOTS), "Invalid robot mode must fail atomically");
        edit(robots, "use_config = perhaps", "use_config = true");
        ini.load();

        // Legacy bcce_version remains an ignored but readable metadata field.
        edit(machines, "schema_version = 1", "schema_version = 1\nbcce_version = 8.0.23");
        edit(robots, "schema_version = 1", "schema_version = 1\nbcce_version = 8.0.24");
        ini.load();
        expectMode(ini, true, true);
        expect(ini.warnings().stream().noneMatch(s -> s.contains("bcce_version")),
            "Legacy version metadata must be ignored");
        expect(read(machines).contains("bcce_version = 8.0.23"), "Migration must preserve old metadata");

        // Migrate the former shared switch to both independent switches, then
        // prove that changing one no longer changes the other.
        Path oldDir = Files.createTempDirectory("bcce-old-modpack-switch-");
        BalanceIni old = new BalanceIni(oldDir);
        old.load();
        Path oldMachines = oldDir.resolve(FileKind.MACHINES.name);
        Path oldRobots = oldDir.resolve(FileKind.ROBOTS.name);
        edit(oldMachines, "use_config = false", "modpack_mode = true");
        edit(oldRobots, "use_config = false", "");
        old.load();
        expect(old.useConfig(FileKind.MACHINES) && old.useConfig(FileKind.ROBOTS),
            "Legacy modpack=true must migrate to both enabled switches");
        expect(read(oldMachines).contains("use_config = true") && read(oldRobots).contains("use_config = true"),
            "Migrated independent switches must be written to both files");
        expect(!read(oldMachines).contains("modpack_mode = true"), "Old mode must be removed");
        try (var files = Files.list(oldDir)) {
            expect(files.filter(p -> p.getFileName().toString().endsWith(".bak")).count() == 2,
                "Both original files must be backed up during migration");
        }
        edit(oldMachines, "use_config = true", "use_config = false");
        old.load();
        expect(!old.useConfig(FileKind.MACHINES) && old.useConfig(FileKind.ROBOTS),
            "Migrated flags must remain independent");

        // If one new flag is explicitly set, the other missing one defaults to false,
        // even if an obsolete shared flag remains in the machine file.
        Path mixedDir = Files.createTempDirectory("bcce-explicit-use-config-");
        BalanceIni mixed = new BalanceIni(mixedDir);
        mixed.load();
        Path mixedMachines = mixedDir.resolve(FileKind.MACHINES.name);
        Path mixedRobots = mixedDir.resolve(FileKind.ROBOTS.name);
        edit(mixedMachines, "use_config = false", "use_config = true\nmodpack_mode = true");
        edit(mixedRobots, "use_config = false", "");
        mixed.load();
        expect(mixed.useConfig(FileKind.MACHINES) && !mixed.useConfig(FileKind.ROBOTS),
            "Explicit per-file mode must override old shared metadata");
        expect(read(mixedRobots).contains("use_config = false"), "Missing robot flag must be generated");

        // TOML migration remains available and is independently gated by machine use_config.
        Path migrated = Files.createTempDirectory("bcce-legacy-toml-");
        Files.writeString(migrated.resolve("buildcraftbuilders-common.toml"),
            "[general]\nquarryMaxTasksPerTick = 7\nquarryMaxBlockMineRate = 2.5\n");
        BalanceIni legacy = new BalanceIni(migrated);
        legacy.load();
        expect(!legacy.useConfig(FileKind.MACHINES), "TOML migration must not auto-enable INI");
        expect(legacy.integer(FileKind.MACHINES, "machine.buildcraftbuilders:quarry", "max_tasks_per_tick", 4) == 4,
            "Built-in quarry balance must remain active");
        edit(migrated.resolve(FileKind.MACHINES.name), "use_config = false", "use_config = true");
        legacy.load();
        expect(legacy.integer(FileKind.MACHINES, "machine.buildcraftbuilders:quarry", "max_tasks_per_tick", 4) == 7,
            "TOML value must migrate to the machine INI");
        expect(!legacy.useConfig(FileKind.ROBOTS), "Migrating machine INI must not enable robot INI");
        System.out.println("BalanceIni independent use_config tests passed");
    }
}
