#!/usr/bin/env python3
"""Verify that each delivered complete Guide Java source has all safety guards.

This test never changes any source file and needs no Apply-fix script. It catches
accidental omissions of a target-specific override during overlay repackaging.
"""
from __future__ import annotations
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
GUI = (
    'version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'source-families/1.21.X/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'source-families/26.X/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
    'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/lib/client/guide/GuiGuide.java',
)
CACHE = (
    'source-families/1.21.X/src/main/java/buildcraft/lib/net/GuideRecipeDisplayCache.java',
    'source-families/26.X/src/main/java/buildcraft/lib/net/GuideRecipeDisplayCache.java',
)
RECIPE_BOOK = (
    'source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft/recipe/BCRecipeDisplays.java',
    'source-families/26.X/src/main/java/buildcraft/lib/compat/minecraft/recipe/BCRecipeDisplays.java',
)
SYNC = (
    'source-families/1.21.X/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
    'source-families/26.X/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
    'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
    'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/lib/net/MessageGuideRecipeDisplays.java',
)

class GuideOverlayIntegrityTest(unittest.TestCase):
    def test_all_native_guide_screens_are_complete_replacements(self):
        for i, path in enumerate(GUI):
            with self.subTest(source=path):
                content = (ROOT/path).read_text(encoding='utf-8')
                self.assertGreater(len(content), 80000)
                for marker in (
                    'import buildcraft.lib.guide.GuideFaultIsolation;',
                    'GuideFaultIsolation.warn("page block"',
                    'GuideFaultIsolation.warn("guide open"',
                    'GuideFaultIsolation.warn("recipe rendering"',
                    'catch (RuntimeException | LinkageError',
                    'private DocumentLayout layoutDocument(',
                ):
                    self.assertIn(marker, content)
                if i < 2:
                    self.assertIn('GuideFaultIsolation.value("recipe usage"', content)
                elif i == 2:
                    self.assertIn('GuideFaultIsolation.value("assembly recipe"', content)
                else:
                    self.assertIn('GuideFaultIsolation.value("guide preview"', content)

    def test_recipe_cache_and_client_book_isolation(self):
        for path in CACHE:
            with self.subTest(source=path):
                content = (ROOT/path).read_text(encoding='utf-8')
                self.assertIn('GuideFaultIsolation.value("cached recipe"',content)
                self.assertIn('GuideFaultIsolation.value("fallback recipe"',content)
        for path in RECIPE_BOOK:
            with self.subTest(source=path):
                content = (ROOT/path).read_text(encoding='utf-8')
                self.assertIn('GuideFaultIsolation.value("recipe collection"',content)
                self.assertIn('GuideFaultIsolation.value("client recipe preview"',content)

    def test_wire_sources_and_common_fault_guard(self):
        for path in SYNC:
            with self.subTest(source=path):
                content = (ROOT/path).read_text(encoding='utf-8')
                self.assertIn('RecipeDisplayEntry.STREAM_CODEC',content)
                self.assertIn('GuideFaultIsolation',content)
                self.assertIn('MAX_PACKET_BYTES',content)
        guard = (ROOT/'source-shared/src/main/java/buildcraft/lib/guide/GuideFaultIsolation.java').read_text()
        self.assertIn('catch (RuntimeException | LinkageError exception)',guard)
        self.assertIn('MAX_DISTINCT_WARNINGS',guard)

if __name__ == '__main__':
    unittest.main(verbosity=2)
