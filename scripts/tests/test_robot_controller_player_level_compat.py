"""Protect the Robot Processor controller and menu from Player.level API drift.

Minecraft 1.19.2 uses the public field. Minecraft 1.20+ uses level().
The project preprocessor must emit only the accessor valid for each target.
"""
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_preprocessor import preprocess_text

FILES = (
    ROOT / "source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java",
    ROOT / "source-shared/src/main/java/buildcraft/robotics/item/ItemRobotController.java",
)
TARGETS = (
    ("1.19.2", "old", "forge"),
    ("1.20.1", "old", "forge"),
    ("1.21.1", "1.21.X", "neoforge"),
    ("1.21.11", "1.21.X", "neoforge"),
    ("26.1.2", "26.X", "neoforge"),
    ("26.2", "26.X", "neoforge"),
    ("26.3", "26.X", "neoforge"),
    ("26.3", "26.X", "fabric"),
)


class RobotControllerPlayerLevelCompatibilityTests(unittest.TestCase):
    def test_each_target_uses_the_correct_player_level_accessor(self):
        for path in FILES:
            source = path.read_text(encoding="utf-8")
            for minecraft, family, loader in TARGETS:
                with self.subTest(file=path.name, minecraft=minecraft, loader=loader):
                    output = preprocess_text(source, minecraft=minecraft, family=family, platform=loader)
                    self.assertNotIn("//?", output)
                    self.assertIn("private static Level playerLevel(Player player)", output)
                    if minecraft == "1.19.2":
                        self.assertIn("return player.level;", output)
                        self.assertNotIn("player.level()", output)
                    else:
                        self.assertIn("return player.level();", output)
                        self.assertNotIn("return player.level;", output)

    def test_call_sites_use_shared_helper_not_version_specific_access(self):
        for path in FILES:
            with self.subTest(file=path.name):
                source = path.read_text(encoding="utf-8")
                self.assertIn("playerLevel(player)", source)
                self.assertEqual(len(re.findall(r"player\.level\(\)", source)), 1,
                                 "Only the new-version helper branch may call player.level()")
                self.assertEqual(len(re.findall(r"player\.level;", source)), 1,
                                 "Only the old-version helper branch may use player.level")


if __name__ == "__main__":
    unittest.main()
