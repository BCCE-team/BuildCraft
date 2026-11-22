"""Source regression checks for the Control Panel's experimental item warning."""

import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text

ROOT = Path(__file__).resolve().parents[2]
ITEM = ROOT / "source-shared/src/main/java/buildcraft/robotics/item/ItemRobotProcessor.java"
REGISTRATIONS = (
    "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/BCRoboticsItems.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/BCRoboticsItems.java",
    "source-families/1.21.X/src/main/java/buildcraft/robotics/BCRoboticsItems.java",
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


class RobotProcessorExperimentalTooltip(unittest.TestCase):
    def test_only_processor_item_uses_warning_subclass(self):
        for rel in REGISTRATIONS:
            with self.subTest(file=rel):
                content = (ROOT / rel).read_text(encoding="utf-8")
                self.assertIn("import buildcraft.robotics.item.ItemRobotProcessor;", content)
                self.assertIn("new ItemRobotProcessor(BCRoboticsBlocks.ROBOT_PROCESSOR.get()", content)
                self.assertIn("new BlockItem(BCRoboticsBlocks.ZONE_PLANNER.get()", content)
                self.assertIn("new BlockItem(BCRoboticsBlocks.REQUESTER.get()", content)

    def test_red_warning_and_parent_tooltip_in_all_targets(self):
        source = ITEM.read_text(encoding="utf-8")
        for mc, family, platform in TARGETS:
            with self.subTest(mc=mc, platform=platform):
                code = preprocess_text(source, minecraft=mc, family=family, platform=platform)
                self.assertEqual(code.count("void appendHoverText("), 1)
                self.assertEqual(code.count("super.appendHoverText("), 1)
                self.assertIn('Component.literal("Experimental").withStyle(ChatFormatting.RED)', code)
                self.assertNotIn("//?", code)
                if mc in ("1.19.2", "1.20.1"):
                    self.assertIn("Level level,", code)
                    self.assertNotIn("Item.TooltipContext context", code)
                elif mc == "1.21.1":
                    self.assertIn("Item.TooltipContext context,", code)
                    self.assertNotIn("TooltipDisplay display", code)
                else:
                    self.assertIn("TooltipDisplay display,", code)


if __name__ == "__main__":
    unittest.main()
