#!/usr/bin/env python3
"""Ensure Filler Planner background takes its original PNG from source-shared.

Independent of the inherited Filler GUI base texture. This test should
continue to pass when the maintainer edits the shared PNG in an IDE.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import target_layout  # noqa: E402

TEXTURE = Path('src/main/resources/assets/buildcraftbuilders/textures/gui/filler_planner.png')
GUI = Path('src/main/resources/assets/buildcraftbuilders/gui/filler_planner.json')
MODERN = (
    '1.21.1-neoforge',
    '1.21.11-neoforge',
    '26.1.2-neoforge',
    '26.2-neoforge',
    '26.3-neoforge',
)


class FillerPlannerSharedBackgroundTest(unittest.TestCase):
    def test_planner_background_is_explicit(self) -> None:
        gui = ROOT / 'source-shared' / GUI
        data = json.loads(gui.read_text(encoding='utf-8'))
        self.assertEqual(data['texture'], 'buildcraftbuilders:gui/filler_planner')
        background = data['elements_below']['background']
        self.assertEqual(background['source']['texture'], 'buildcraftbuilders:gui/filler_planner')
        self.assertEqual(background['source']['area'], [0, 0, 176, 81])
        self.assertEqual(background['size'], [176, 81])

    def test_modern_targets_use_shared_gui_png(self) -> None:
        for target in MODERN:
            with self.subTest(target=target):
                layout = target_layout(target)
                self.assertEqual(layout.source_candidates(TEXTURE), (ROOT / 'source-shared' / TEXTURE,))
                self.assertEqual(layout.source_candidates(GUI), (ROOT / 'source-shared' / GUI,))

    def test_shared_png_present(self) -> None:
        png = ROOT / 'source-shared' / TEXTURE
        self.assertTrue(png.is_file())
        self.assertEqual(png.read_bytes()[:8], b'\x89PNG\r\n\x1a\n')


if __name__ == '__main__':
    unittest.main()
