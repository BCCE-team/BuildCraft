"""Regression guards for newer Minecraft item-use, GUI and scan helpers."""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source
from source_preprocessor import preprocess_text, strip_source_condition


SCHEDULER = "src/main/java/buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java"
CONTROLLER = "src/main/java/buildcraft/robotics/item/ItemRobotController.java"
GUI = "src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java"
NEW_TARGETS = ("1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge", "26.3-fabric")
OLD_TARGETS = ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge")


def effective_source(target, path):
    props = load_properties()
    layout = target_layout(target, props)
    source = resolve_effective_source(layout, props, path)
    if source is None:
        raise AssertionError(f"No {path} for {target}")
    raw, _ = strip_source_condition(source.read_text(encoding="utf-8"))
    return preprocess_text(raw, minecraft=props[f"target.{target}.deps.minecraft"],
                           family=layout.family, platform=layout.platform,
                           source=str(source.relative_to(ROOT)))


class RobotProcessorModernCompileTests(unittest.TestCase):
    def test_item_use_returns_modern_interaction_result(self):
        for target in NEW_TARGETS:
            with self.subTest(target=target):
                code = effective_source(target, CONTROLLER)
                self.assertNotIn("import net.minecraft.world.InteractionResultHolder;", code)
                self.assertIn("public InteractionResult use(Level world, Player player, InteractionHand hand)", code)
                self.assertNotIn("new InteractionResultHolder", code)

    def test_older_item_use_contract_is_preserved(self):
        for target in OLD_TARGETS:
            with self.subTest(target=target):
                code = effective_source(target, CONTROLLER)
                self.assertIn("import net.minecraft.world.InteractionResultHolder;", code)
                self.assertIn("public InteractionResultHolder<ItemStack> use(", code)

    def test_scanner_uses_separate_section_base_expression(self):
        for target in (*NEW_TARGETS, *OLD_TARGETS):
            with self.subTest(target=target):
                code = effective_source(target, SCHEDULER)
                self.assertIn("int minSection = level.getMinBuildHeight() >> 4;", code)
                self.assertIn("int index = sectionY - minSection;", code)
                self.assertNotIn("(level.getMinBuildHeight() >> 4)", code)

    def test_12111_gui_uses_compatible_item_and_deferred_tooltips(self):
        code = effective_source("1.21.11-neoforge", GUI)
        self.assertNotIn(".item(stack,", code)
        self.assertNotIn("graphics.renderTooltip(", code)
        self.assertNotIn("if (hasShiftDown())", code)
        self.assertNotIn("? (hasShiftDown()", code)
        self.assertIn("getActiveGraphics().renderItem(stack, x, y)", code)
        self.assertIn("RobotProcessorPreview.render(getActiveGraphics(), stack,", code)
        self.assertNotIn("graphics.renderItem(stack, -8, -8)", code)
        self.assertIn("RenderCompat.renderComponentTooltip(graphics, font, detail", code)
        self.assertIn("RenderCompat.renderTooltip(graphics, font, hovered.stack()", code)
        self.assertIn("RenderCompat.hasInputShiftDown()", code)
        self.assertIn("public boolean keyReleased(net.minecraft.client.input.KeyEvent event)", code)

    def test_26x_gui_modifier_tracking(self):
        for target in NEW_TARGETS[1:]:
            with self.subTest(target=target):
                code = effective_source(target, GUI)
                self.assertNotIn("if (hasShiftDown())", code)
                self.assertNotIn("? (hasShiftDown()", code)
                self.assertIn("RenderCompat.hasInputShiftDown()", code)
                self.assertIn("public boolean keyReleased(net.minecraft.client.input.KeyEvent event)", code)

    def test_1211_downport_preserves_older_gui(self):
        code = effective_source("1.21.1-neoforge", GUI)
        self.assertIn("getActiveGraphics().renderItem(stack, x, y)", code)
        self.assertNotIn("shiftKeyHeld", code)


# Consolidated related checks from test_small_text_apis.py.
import unittest

from pathlib import Path

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
