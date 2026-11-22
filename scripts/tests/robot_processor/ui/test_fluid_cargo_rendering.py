"""Regression guard for the flowing-fluid preview in the robot cargo GUI."""

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source
from source_preprocessor import preprocess_text, strip_source_condition

RENDERER = "src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java"
GUI = "src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java"
SIGNATURE = re.compile(
    r"public\s+static\s+void\s+drawFlowingFluidForGui\s*\(\s*Fluid\s+fluid\s*,\s*"
    r"double\s+sx\s*,\s*double\s+sy\s*,\s*double\s+ex\s*,\s*double\s+ey\s*,\s*"
    r"(?P<graphics>\w+)\s+\w+\s*\)"
)


def materialized_source(target: str, logical_path: str) -> str:
    properties = load_properties()
    layout = target_layout(target, properties)
    path = resolve_effective_source(layout, properties, logical_path)
    if path is None:
        raise AssertionError(f"{target} lacks {logical_path}")
    text, _ = strip_source_condition(path.read_text(encoding="utf-8"))
    return preprocess_text(
        text,
        minecraft=properties[f"target.{target}.deps.minecraft"],
        family=layout.family,
        platform=layout.platform,
        source=str(path.relative_to(ROOT)),
    )


class RobotProcessorFlowingCargoGuiTest(unittest.TestCase):
    def test_legacy_neoforge_has_pose_flowing_fluid_overload(self):
        renderer = materialized_source("1.21.1-neoforge", RENDERER)
        gui = materialized_source("1.21.1-neoforge", GUI)
        matches = list(SIGNATURE.finditer(renderer))
        self.assertEqual(["Pose"], [m.group("graphics") for m in matches])
        self.assertIn("sprite = getFluidSprite(FluidSpriteType.FLOWING, fluid, FluidStack.EMPTY);", renderer)
        self.assertIn("IClientFluidTypeExtensions.of(fluid).getTintColor()", renderer)
        self.assertIn("pose.last()", gui.split("private void renderCargoFluid(", 1)[1].split("}", 1)[0])

    def test_new_neoforge_versions_retain_native_gui_overloads(self):
        for target, native in (
            ("1.21.11-neoforge", "GuiGraphics"),
            ("26.2-neoforge", "GuiGraphicsExtractor"),
            ("26.3-neoforge", "GuiGraphicsExtractor"),
        ):
            with self.subTest(target=target):
                renderer = materialized_source(target, RENDERER)
                matches = list(SIGNATURE.finditer(renderer))
                self.assertIn(native, [m.group("graphics") for m in matches])


if __name__ == "__main__":
    unittest.main()
