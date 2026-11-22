#!/usr/bin/env python3
"""Regression: control panel and controller sprites must be stitched on 1.20.1+."""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source
from transforms.resources import generate_legacy_resources_from_modern_canonical

ATLAS_PATH = 'src/main/resources/assets/minecraft/atlases/blocks.json'
ASSETS = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics'
SPRITES = {
    'buildcraftrobotics:blocks/robot_processor/down',
    'buildcraftrobotics:blocks/robot_processor/front',
    'buildcraftrobotics:blocks/robot_processor/top',
    'buildcraftrobotics:items/controller',
}


def read_sprites(path: Path) -> dict[str, int]:
    data = json.loads(path.read_text(encoding='utf-8'))
    counts: dict[str, int] = {}
    for source in data['sources']:
        if 'resource' in source:
            resource = source['resource']
            if resource in SPRITES:
                assert source['type'] == 'minecraft:single', (path, source)
            counts[resource] = counts.get(resource, 0) + 1
    return counts


class TestRobotProcessorTextureAtlas(unittest.TestCase):
    def test_referenced_textures_exist_and_are_valid(self):
        model = json.loads((ASSETS / 'models/block/robot_processor.json').read_text(encoding='utf-8'))
        controller = json.loads((ASSETS / 'models/item/controller.json').read_text(encoding='utf-8'))
        referenced = set(model['textures'].values()) | set(controller['textures'].values())
        self.assertEqual(SPRITES, referenced)
        for resource in SPRITES:
            namespace, path = resource.split(':', 1)
            texture = (ROOT / 'source-shared/src/main/resources/assets' /
                       namespace / 'textures' / (path + '.png'))
            with self.subTest(resource=resource):
                self.assertTrue(texture.is_file(), texture)
                self.assertEqual(b'\x89PNG\r\n\x1a\n', texture.read_bytes()[:8])
        frame = json.loads((ASSETS / 'textures/blocks/robot_processor/front.png.mcmeta').read_text())
        self.assertEqual(list(range(8)), frame['animation']['frames'])

    def test_modern_source_atlases_declare_every_sprite_once(self):
        for root in (
            ROOT / 'source-families/1.21.X',
            ROOT / 'source-downports/1.21.X/1.21.1/neoforge',
        ):
            with self.subTest(root=root):
                entries = read_sprites(root / ATLAS_PATH)
                for resource in SPRITES:
                    self.assertEqual(1, entries.get(resource), resource)

    def test_all_neoforge_targets_resolve_sprite_sources(self):
        props = load_properties()
        for target in ('1.21.1-neoforge', '1.21.11-neoforge',
                       '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
                       '26.3-fabric'):
            with self.subTest(target=target):
                path = resolve_effective_source(target_layout(target, props), props, ATLAS_PATH)
                self.assertIsNotNone(path)
                entries = read_sprites(path)
                for resource in SPRITES:
                    self.assertEqual(1, entries.get(resource), (target, resource))

    def test_legacy_120_atlas_generated_from_canonical(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            self.assertGreater(generate_legacy_resources_from_modern_canonical(
                dest, minecraft='1.20.1', family='old', platform='forge'), 0)
            entries = read_sprites(dest / ATLAS_PATH)
            for resource in SPRITES:
                self.assertEqual(1, entries.get(resource), resource)

    def test_119_does_not_need_modern_atlas(self):
        props = load_properties()
        path = resolve_effective_source(target_layout('1.19.2-forge', props), props, ATLAS_PATH)
        self.assertIsNone(path)


# Consolidated related checks from test_experimental_label.py.
import unittest

from pathlib import Path

from scripts.source_preprocessor import preprocess_text

ITEM = ROOT / "source-shared/src/main/java/buildcraft/robotics/item/ItemRobotProcessor.java"

REGISTRATIONS = (
    "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/BCRoboticsItems.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/BCRoboticsItems.java",
    "source-families/1.21.X/src/main/java/buildcraft/robotics/BCRoboticsItems.java",
)

TARGETS = (
    ("1.19.2", "old", "forge"),
    ("1.20.1", "old", "forge"),
    ("1.21.1", "1.21.X", "neoforge"),
    ("1.21.11", "1.21.X", "neoforge"),
    ("26.1.2", "26.X", "neoforge"),
    ("26.2", "26.X", "neoforge"),
    ("26.3", "26.X", "neoforge"),
    ("26.3", "26.X", "fabric"),
)

class RobotProcessorExperimentalTooltip(unittest.TestCase):
    def test_only_processor_item_uses_warning_subclass(self):
        for rel in REGISTRATIONS:
            with self.subTest(file=rel):
                content = (ROOT / rel).read_text(encoding="utf-8")
                self.assertIn("import buildcraft.robotics.item.ItemRobotProcessor;", content)
                self.assertIn("new ItemRobotProcessor(BCRoboticsBlocks.ROBOT_PROCESSOR.get()", content)
                self.assertIn("new BlockItem(BCRoboticsBlocks.ZONE_PLANNER.get()", content)
                self.assertIn("new BlockItem(BCRoboticsBlocks.REQUESTER.get()", content)

    def test_red_warning_and_parent_tooltip_in_all_targets(self):
        source = ITEM.read_text(encoding="utf-8")
        for mc, family, platform in TARGETS:
            with self.subTest(mc=mc, platform=platform):
                code = preprocess_text(source, minecraft=mc, family=family, platform=platform)
                self.assertEqual(code.count("void appendHoverText("), 1)
                self.assertEqual(code.count("super.appendHoverText("), 1)
                self.assertIn('Component.literal("Experimental").withStyle(ChatFormatting.RED)', code)
                self.assertNotIn("//?", code)
                if mc in ("1.19.2", "1.20.1"):
                    self.assertIn("Level level,", code)
                    self.assertNotIn("Item.TooltipContext context", code)
                elif mc == "1.21.1":
                    self.assertIn("Item.TooltipContext context,", code)
                    self.assertNotIn("TooltipDisplay display", code)
                else:
                    self.assertIn("TooltipDisplay display,", code)


if __name__ == "__main__":
    unittest.main()
