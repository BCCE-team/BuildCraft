#!/usr/bin/env python3
"""Regression: control panel and controller sprites must be stitched on 1.20.1+."""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
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


if __name__ == '__main__':
    unittest.main()
