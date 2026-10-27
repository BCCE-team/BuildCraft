"""Regression checks for independently switched INI balance configuration."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def content(path):
    return (ROOT / path).read_text(encoding="utf-8")


def test_machine_balance_config_is_ini_only():
    core = content("source-shared/src/main/java/buildcraft/core/BCCoreConfig.java")
    for key in ("propPumpMaxDistance", "propMarkerMaxDistance", "propMiningMultiplier",
                "propMiningMaxDepth", "propPumpsConsumeWater"):
        assert key not in core, key
    quarry = content("source-shared/src/main/java/buildcraft/builders/BCBuildersConfig.java")
    assert "BCConfigSpec" not in quarry
    assert "BalanceConfig.machineInt" in quarry
    for branch in ("version-src/1.19.2-forge/src/main/java/buildcraft/builders/BCBuilders.java",
                   "version-src/1.20.1-forge/src/main/java/buildcraft/builders/BCBuilders.java",
                   "source-platforms/neoforge/src/main/java/buildcraft/builders/BCBuilders.java"):
        assert "registerConfig(Type.COMMON, ConfigBinding.bind(BCBuildersConfig.config))" not in content(branch)


def test_no_warning_screen_or_hooks():
    for root in ("source-families", "source-family-platforms", "source-downports", "version-src"):
        for path in (ROOT / root).rglob("*.java"):
            assert "BalanceNoticeScreen" not in path.read_text(encoding="utf-8"), path
    assert not list(ROOT.rglob("BalanceNoticeScreen.java"))
    assert not list(ROOT.rglob("BalanceNoticeView.java"))


def test_switches_are_independent_and_mode_is_renamed():
    ini = content("source-shared/src/main/java/buildcraft/lib/balance/BalanceIni.java")
    config = content("source-shared/src/main/java/buildcraft/lib/balance/BalanceConfig.java")
    assert '"use_config = false' in ini
    assert 'private volatile Map<FileKind, Boolean> enabledFiles' in ini
    assert 'public boolean useConfig(FileKind kind)' in ini
    assert 'if (!useConfig(kind)) return fallback;' in ini
    assert 'ini != null && ini.useConfig(kind) ? ini : null' in config
    assert 'active(BalanceIni.FileKind.MACHINES)' in config
    assert 'active(BalanceIni.FileKind.ROBOTS)' in config
    assert 'built-in BCCE values' in config
    assert 'BalanceNoticeScreen' not in config
    assert 'new BalanceIni(Path.of("config"))' in config
    assert 'modpackMode()' not in ini + config
    assert 'private static final String MODPACK_MODE_KEY =' not in ini
    assert 'LEGACY_MODPACK_MODE_KEY' in ini  # Read only for migration.
    assert '"bcce_version = "' not in ini
    assert '"meta/bcce_version"' in ini  # Accepted legacy metadata.
    assert 'acknowledge(' not in ini
    assert 'resetToDefaults' not in config


def test_generated_defaults_describe_per_file_independence():
    ini = content("source-shared/src/main/java/buildcraft/lib/balance/BalanceIni.java")
    assert 'Switching this file does NOT change the other balance file' in ini
    assert 'use_config = false' in ini
    assert 'kind == FileKind.MACHINES ? "machine" : "robot"' in ini
    assert 'migrateSwitch(kind, nextModes.get(kind)' in ini
    docs = content('docs/BALANCE_INI.md')
    assert 'Machine `use_config`' in docs and 'Robot `use_config`' in docs
    assert '`modpack_mode`' in docs  # Document upgrade procedure only.


def test_pump_capacity_and_robot_energy_safety():
    for platform in ("forge", "neoforge"):
        pump = content(f"source-platforms/{platform}/src/main/java/buildcraft/factory/tile/TilePump.java")
        assert "tankCapacity" in pump
        assert "reconcileTankCapacity()" in pump
        assert "tank.getFluidAmount() <= configured" in pump
        assert "energy_per_bucket_mj" in pump
    robot_ai = content("source-shared/src/main/java/buildcraft/robotics/internal/legacy/robots/AIRobot.java")
    assert "BalanceConfig.robot(key, \"energy_cost_multiplier\"" in robot_ai
    assert "BCRoboticsBoards.getById" not in robot_ai
    assert "movement_speed_multiplier" in content(
        "source-shared/src/main/java/buildcraft/robotics/ai/AIRobotGoto.java")


def test_java_balance_config_runtime_all_combinations():
    with tempfile.TemporaryDirectory(prefix="bcce-balance-runtime-") as tmp:
        tmp_path = Path(tmp)
        classes = tmp_path / "classes"
        classes.mkdir()
        subprocess.run([
            "javac", "-d", str(classes),
            str(ROOT / "source-shared/src/main/java/buildcraft/lib/balance/BalanceIni.java"),
            str(ROOT / "source-shared/src/main/java/buildcraft/lib/balance/BalanceConfig.java"),
            str(ROOT / "scripts/tests/BalanceConfigRuntimeTester.java"),
        ], check=True)
        for machine, robot in (("false", "false"), ("false", "true"),
                               ("true", "false"), ("true", "true")):
            folder = tmp_path / f"machine-{machine}-robot-{robot}"
            folder.mkdir()
            subprocess.run([
                "java", "-cp", str(classes), "BalanceConfigRuntimeTester", machine, robot,
            ], cwd=folder, check=True)


def test_java_balance_ini():
    with tempfile.TemporaryDirectory(prefix="bcce-balance-build-") as tmp:
        subprocess.run([
            "javac", "-d", tmp,
            str(ROOT / "source-shared/src/main/java/buildcraft/lib/balance/BalanceIni.java"),
            str(ROOT / "scripts/tests/BalanceIniTester.java"),
        ], check=True)
        subprocess.run(["java", "-cp", tmp, "BalanceIniTester"], check=True)


if __name__ == "__main__":
    for name, check in sorted(globals().items()):
        if name.startswith("test_") and callable(check):
            check()
            print("PASS", name)
