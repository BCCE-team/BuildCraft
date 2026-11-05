"""Regression guard: Filler reads the editable 64x64 PNG directly on modern targets."""
from __future__ import annotations

import json
from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, resolve_effective_source, target_layout

ASSET = 'src/main/resources/assets/buildcraftbuilders/gui/filler_base.json'
PNG = ROOT / 'source-shared/src/main/resources/assets/buildcraftbuilders/textures/gui/filler_buttons.png'
ATLAS = 'buildcraftbuilders:gui/filler_buttons'
MODES = {
    'button_preview': {'enabled': (0, 0), 'hovered': (0, 16), 'active': (16, 0), 'active_hovered': (16, 16)},
    'button_excavate': {'enabled': (32, 0), 'hovered': (32, 16), 'active': (48, 0), 'active_hovered': (48, 16)},
    'button_invert': {'enabled': (0, 32), 'hovered': (0, 48), 'active': (16, 32), 'active_hovered': (16, 48)},
}

class FillerButtonAtlasTest(unittest.TestCase):
    def test_editable_png_is_64px(self):
        data = PNG.read_bytes()
        self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
        self.assertEqual(struct.unpack('>II', data[16:24]), (64, 64))

    def test_modern_gui_uses_direct_png_not_prebaked_filler_background(self):
        properties = load_properties()
        for target in ('1.21.1-neoforge', '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                selected = resolve_effective_source(target_layout(target, properties), properties, ASSET)
                self.assertIsNotNone(selected)
                self.assertIn('source-families/', selected.as_posix())
                data = json.loads(selected.read_text('utf-8'))
                for name, modes in MODES.items():
                    button = data['types'][name]
                    self.assertEqual(button['texture'], ATLAS)
                    self.assertEqual(button['texture_size'], 64)
                    self.assertEqual(button['size'], [16, 16])
                    for state, uv in modes.items():
                        self.assertEqual(button['modes'][state], list(uv))
                        self.assertLessEqual(uv[0] + 16, 64)
                        self.assertLessEqual(uv[1] + 16, 64)

    def test_old_family_remains_on_original_background(self):
        properties = load_properties()
        for target in ('1.19.2-forge','1.20.1-forge'):
            with self.subTest(target=target):
                selected = resolve_effective_source(target_layout(target, properties), properties, ASSET)
                self.assertIsNotNone(selected)
                data = json.loads(selected.read_text('utf-8'))
                self.assertEqual(data['texture'], 'buildcraftbuilders:gui/filler')
                self.assertNotIn('texture', data['types']['button_preview'])

    def test_modern_button_renderer_handles_real_atlas_dimensions(self):
        for family in ('1.21.X','26.X'):
            java = (ROOT/f'source-families/{family}/src/main/java/buildcraft/lib/gui/json/ElementTypeButton.java').read_text('utf-8')
            self.assertIn('texSize = resolveEquationInt(json, key + ".texture_size", ctx);', java)
            self.assertIn('uvs[2], uvs[3], texSize)', java)
            for name in ('enabled','hovered','active','active_hovered','disabled','active_disabled'):
                self.assertIn(f'"modes.{name}.texture_size"', java)

if __name__ == '__main__':
    unittest.main()
