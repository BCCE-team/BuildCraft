#!/usr/bin/env python3
"""Guard against 26.X requester downports shadowing component-exact matching."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout
from source_layout import resolve_effective_source


class RequesterComponentMatchingTests(unittest.TestCase):
    def test_resolved_neoforge_requesters_keep_exact_matching(self):
        props = load_properties()
        relative = "src/main/java/buildcraft/robotics/tile/TileRequester.java"
        for target in (
            "1.21.1-neoforge", "1.21.11-neoforge",
            "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
        ):
            with self.subTest(target=target):
                source = resolve_effective_source(target_layout(target, props), props, relative)
                self.assertIsNotNone(source, f"{target}: missing TileRequester")
                text = source.read_text(encoding="utf-8")
                self.assertIn("private static boolean matchesRequestedStack(", text)
                self.assertIn("return StackUtil.isMatchingItem(expected, actual, true, true);", text)
                self.assertIn("BuildCraftServices.ITEM_LISTS).isList(expected)", text)
                self.assertEqual(text.count("matchesRequestedStack("), 9)
                self.assertNotIn("StackUtil.isMatchingItemOrList(template, offered)", text)

    def test_request_support_does_not_discard_item_components(self):
        source = ROOT / "source-shared/src/main/java/buildcraft/robotics/internal/api2/RequestSupport.java"
        text = source.read_text(encoding="utf-8")
        self.assertIn("StackUtil.matchesStackOrList(template, candidate)", text)
        self.assertNotIn("StackUtil.isMatchingItemOrList(template, candidate)", text)


if __name__ == "__main__":
    unittest.main()
