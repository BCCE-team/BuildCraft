#!/usr/bin/env python3
"""The glass-like tank must be quick to break and recoverable without a tool."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_ids, target_layout
from source_layout import resolve_effective_source, _materialize_text_file


class TankBreakingParity(unittest.TestCase):
    def test_all_targets_have_glass_hardness_and_no_required_tool(self):
        props = load_properties()
        for target in target_ids(props):
            with self.subTest(target=target):
                layout = target_layout(target, props)
                minecraft = props[f'target.{target}.deps.minecraft']
                def effective(relative):
                    source = resolve_effective_source(layout, props, relative)
                    if source is None:
                        return None
                    with tempfile.TemporaryDirectory() as td:
                        out = Path(td) / source.name
                        _materialize_text_file(source, out, logical_relative=relative,
                                               minecraft=minecraft, family=layout.family,
                                               platform=layout.platform, preprocess=True)
                        return out.read_text(encoding='utf-8')

                block = effective('src/main/java/buildcraft/factory/block/BlockTank.java')
                self.assertIsNotNone(block)
                self.assertIn('.strength(0.3f, 10.0f)', block)
                self.assertNotIn('requiresCorrectToolForDrops()', block)
                tag_dir = 'blocks' if layout.family == 'old' else 'block'
                pickaxe = effective(f'src/main/resources/data/minecraft/tags/{tag_dir}/mineable/pickaxe.json')
                self.assertIsNotNone(pickaxe)
                self.assertNotIn('buildcraftfactory:tank', json.loads(pickaxe)['values'])
                provider = effective('src/main/java/buildcraft/lib/BCTagsProvider.java')
                if provider is not None:
                    self.assertNotIn('BCFactoryBlocks.TANK_BLOCK.get()', provider)
                loot_dir = 'loot_tables' if layout.family == 'old' else 'loot_table'
                loot = effective(f'src/main/resources/data/buildcraftfactory/{loot_dir}/blocks/tank.json')
                self.assertIsNotNone(loot)
                entries = [entry for pool in json.loads(loot)['pools'] for entry in pool['entries']]
                self.assertTrue(any(entry.get('type') == 'minecraft:item' and
                                    entry.get('name') == 'buildcraftfactory:tank' for entry in entries))


if __name__ == '__main__':
    unittest.main()
