"""Regression checks for independently switched INI balance configuration."""
from pathlib import Path
import unittest
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


class BalanceIntegrationTest(unittest.TestCase):
    """Expose the existing checks to CI unittest discovery."""

    def test_machine_balance_config_is_ini_only(self):
        test_machine_balance_config_is_ini_only()

if __name__ == "__main__":
    unittest.main()
