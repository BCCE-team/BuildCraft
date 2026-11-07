#!/usr/bin/env python3
"""Guard the baked Programming Table glass in every Minecraft source generation.

This pane must be visible both as a placed block and as an inventory item.
Do not depend on a block-entity renderer: those do not render inventory models
and their native submission path differs between Minecraft generations.
"""
from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_layout import load_properties, resolve_effective_source, target_layout

ASSET_ROOT = Path("src/main/resources/assets/buildcraftsilicon")
TARGETS = (
    "1.19.2-forge",
    "1.20.1-forge",
    "1.21.1-neoforge",
    "1.21.11-neoforge",
    "26.1.2-neoforge",
    "26.2-neoforge",
    "26.3-neoforge",
)


def asset_json(relative: str) -> dict:
    return json.loads((ROOT / "source-shared" / ASSET_ROOT / relative).read_text(encoding="utf-8"))


class ProgrammingTableGlass(unittest.TestCase):
    def test_world_and_item_use_a_visible_glass_texture(self):
        blockstate = asset_json("blockstates/programming_table.json")
        self.assertEqual(
            "buildcraftsilicon:block/table/programming/transparent",
            blockstate["variants"][""]["model"],
        )
        pane = asset_json("models/block/table/programming/transparent.json")
        self.assertEqual("buildcraftsilicon:block/table/programming", pane["parent"])
        self.assertEqual("minecraft:block/white_stained_glass", pane["textures"]["glass"])
        self.assertEqual("minecraft:translucent", pane["render_type"])

        item = asset_json("models/item/programming_table.json")
        self.assertEqual("buildcraftsilicon:block/table/programming/transparent", item["parent"])
        self.assertEqual("minecraft:translucent", item["render_type"])

        base = asset_json("models/block/table/programming.json")
        glass = [
            (element, face)
            for element in base["elements"]
            for face in element.get("faces", {}).values()
            if face.get("texture") == "#glass"
        ]
        self.assertEqual(1, len(glass), "the model needs one glass pane, not repeated quads")
        element, face = glass[0]
        self.assertEqual([4, 9, 4], element["from"])
        self.assertEqual([12, 9, 12], element["to"])
        # The previous BER sampled the middle 8x8 texels. Keep the same appearance.
        self.assertEqual([4, 4, 12, 12], face["uv"])

    def test_all_seven_targets_use_the_shared_glass_and_item_model(self):
        props = load_properties()
        resources = (
            "blockstates/programming_table.json",
            "models/block/table/programming.json",
            "models/block/table/programming/transparent.json",
            "models/item/programming_table.json",
        )
        for target in TARGETS:
            layout = target_layout(target, props)
            for relative in resources:
                with self.subTest(target=target, asset=relative):
                    expected = ROOT / "source-shared" / ASSET_ROOT / relative
                    actual = resolve_effective_source(layout, props, ASSET_ROOT / relative)
                    self.assertEqual(expected, actual)
            if target.startswith("26."):
                wrapper_path = ROOT / "source-families/26.X/src/main/resources/assets/buildcraftsilicon/items/programming_table.json"
                wrapper = json.loads(wrapper_path.read_text(encoding="utf-8"))
                self.assertEqual("minecraft:model", wrapper["model"]["type"])
                self.assertEqual("buildcraftsilicon:item/programming_table", wrapper["model"]["model"])

    def test_no_duplicate_block_entity_glass_pass(self):
        catalogue = (ROOT / "source-shared/src/main/java/buildcraft/silicon/BCSiliconClientRenderers.java").read_text(encoding="utf-8")
        self.assertNotIn("RenderProgrammingTable::new", catalogue)
        self.assertIn("PROGRAMMING_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.TRANSLUCENT", catalogue)
        baseline = json.loads((ROOT / "scripts/tests/client_catalog_baseline.json").read_text(encoding="utf-8"))
        for target, values in baseline.items():
            with self.subTest(target=target):
                entries = values["renderers"]["silicon/BCSiliconModels.java"]
                self.assertFalse(any("RenderProgrammingTable::new" in entry[1] for entry in entries))
                self.assertTrue(any("RenderLaser::new" in entry[1] for entry in entries))


if __name__ == "__main__":
    unittest.main()
