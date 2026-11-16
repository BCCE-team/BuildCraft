#!/usr/bin/env python3
"""26.3 Zone Planner source materialization must keep Ctrl hotkeys after GLFW -> SDL conversion."""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))

from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file


class ZonePlannerModifierCompatibilityTests(unittest.TestCase):
    def test_ctrl_bits_preserve_legacy_glfw_and_263_sdl(self):
        mask = 0x0002 | 0x0040 | 0x0080
        for modifier in (0x0002, 0x0040, 0x0080):
            with self.subTest(modifier=modifier):
                self.assertNotEqual(modifier & mask, 0)
        for modifier in (0, 0x0001, 0x0004):
            with self.subTest(modifier=modifier):
                self.assertEqual(modifier & mask, 0)

    def test_materialized_263_fabric_and_neoforge_do_not_reference_glfw(self):
        props = load_properties()
        relative = 'src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java'
        with tempfile.TemporaryDirectory(prefix='bc-zp-ctrl-') as temp:
            for target in ('26.3-fabric', '26.3-neoforge', '26.2-neoforge'):
                with self.subTest(target=target):
                    layout = target_layout(target, props)
                    source = resolve_effective_source(layout, props, relative)
                    self.assertIsNotNone(source)
                    output = Path(temp) / target / relative
                    _materialize_text_file(
                        source, output, logical_relative=relative,
                        minecraft=props[f'target.{target}.deps.minecraft'],
                        family=layout.family, platform=layout.platform, preprocess=True,
                    )
                    code = output.read_text(encoding='utf-8')
                    self.assertIn('private static final int CTRL_MODIFIER_MASK = 0x0002 | 0x0040 | 0x0080;', code)
                    self.assertIn('(modifiers & CTRL_MODIFIER_MASK) != 0', code)
                    self.assertIn('undoEdit();', code)
                    self.assertIn('redoEdit();', code)
                    if target.startswith('26.3'):
                        self.assertNotIn('GLFW.', code)
                        self.assertNotIn('import org.lwjgl.glfw.GLFW;', code)
                        self.assertIn('InputConstants.KEY_RETURN', code)
                    else:
                        self.assertIn('import org.lwjgl.glfw.GLFW;', code)


if __name__ == '__main__':
    unittest.main()
