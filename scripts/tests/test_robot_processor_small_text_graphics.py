"""Guard the GUI text API used in robot processor screens across versions."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

MODERN_GUI_FILES = (
    "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java",
    "source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java",
    "source-families/1.21.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java",
    "source-families/26.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java",
)
LEGACY_GUI = (
    "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java"
)


class RobotProcessorSmallTextGraphicsTest(unittest.TestCase):
    def test_modern_screens_draw_scaled_labels_with_active_graphics(self):
        for path in MODERN_GUI_FILES:
            with self.subTest(path=path):
                src = (ROOT / path).read_text(encoding="utf-8")
                method = src.split("private void panelSmallText(", 1)[1].split("private void panelText(", 1)[0]
                self.assertIn("var graphics = getActiveGraphics();", method)
                self.assertIn("drawString(graphics, font, fitted, 0, 0, color, false);", method)
                self.assertNotIn("drawString(pose, font, fitted", method)

    def test_legacy_1192_still_uses_posestack_overload(self):
        src = (ROOT / LEGACY_GUI).read_text(encoding="utf-8")
        method = src.split("private void panelSmallText(", 1)[1].split("private void panelText(", 1)[0]
        self.assertIn("drawString(pose, font, fitted, 0, 0, color, false);", method)


if __name__ == "__main__":
    unittest.main()
