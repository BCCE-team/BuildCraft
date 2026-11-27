#!/usr/bin/env python3
"""The unplaced Robot Processor must never use the powered front texture."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_ids, target_layout
from source_layout import resolve_effective_source
from transforms.resources import apply_resource_transforms, generate_modern_item_definitions

ASSETS = 'src/main/resources/assets/buildcraftrobotics/'
ITEM = ASSETS + 'models/item/robot_processor.json'
OFF = ASSETS + 'models/block/robot_processor_off.json'
ON = ASSETS + 'models/block/robot_processor.json'
DEFINITION = ASSETS + 'items/robot_processor.json'


class TestRobotProcessorInventoryOffModel(unittest.TestCase):
    def test_inventory_model_is_off_on_every_target(self):
        props = load_properties()
        for target in target_ids(props):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                layout = target_layout(target, props)
                minecraft = props[f'target.{target}.deps.minecraft']
                dest = Path(tmp)
                for rel in (ITEM, OFF, ON, DEFINITION):
                    source = resolve_effective_source(layout, props, rel)
                    if rel != DEFINITION:
                        self.assertIsNotNone(source, rel)
                    if source is None:
                        continue
                    output = dest / rel
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_text(apply_resource_transforms(
                        source.read_text(encoding='utf-8'), minecraft=minecraft,
                        relative=rel), encoding='utf-8')

                generate_modern_item_definitions(dest, minecraft=minecraft,
                                                 platform=layout.platform)
                item = json.loads((dest / ITEM).read_text(encoding='utf-8'))
                off = json.loads((dest / OFF).read_text(encoding='utf-8'))
                on = json.loads((dest / ON).read_text(encoding='utf-8'))
                self.assertEqual('buildcraftrobotics:block/robot_processor_off', item['parent'])
                self.assertEqual('buildcraftrobotics:block/robot_processor', off['parent'])
                self.assertEqual('buildcraftrobotics:blocks/robot_processor/front_off', off['textures']['north'])
                self.assertEqual('buildcraftrobotics:blocks/robot_processor/front', on['textures']['north'])
                if tuple(map(int, minecraft.split('.'))) >= (1, 21, 11):
                    definition = json.loads((dest / DEFINITION).read_text(encoding='utf-8'))
                    self.assertEqual({'type': 'minecraft:model',
                                      'model': 'buildcraftrobotics:item/robot_processor'}, definition['model'])
                else:
                    self.assertFalse((dest / DEFINITION).exists())
