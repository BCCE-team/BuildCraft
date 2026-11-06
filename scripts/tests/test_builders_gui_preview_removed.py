#!/usr/bin/env python3
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java

TARGETS = ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
ARCHITECT = 'src/main/java/buildcraft/builders/gui/GuiArchitectTable.java'
BUILDER = 'src/main/java/buildcraft/builders/gui/GuiBuilder.java'


class BuilderGuiPreviewRemovalTests(unittest.TestCase):
    def test_architect_gui_has_no_screen_preview_or_live_preview_hooks(self):
        for target in TARGETS:
            with self.subTest(target=target):
                code = read_materialized_java(target, ARCHITECT)
                self.assertNotIn('BlueprintScreenPreview.draw(', code)
                self.assertNotIn('ClientArchitectPreview.get(', code)
                self.assertNotIn('ClientArchitectPreview.tick(', code)
                self.assertNotIn('ClientArchitectPreview.clear(', code)

    def test_builder_gui_has_no_screen_preview(self):
        for target in TARGETS:
            with self.subTest(target=target):
                code = read_materialized_java(target, BUILDER)
                self.assertNotIn('BlueprintScreenPreview.draw(', code)


if __name__ == '__main__':
    unittest.main()
