#!/usr/bin/env python3
"""Fabric 26.3 redstone-module parity and normal blockstate model regression tests.

These tests inspect production source and effective assets. They do not replace a
Gradle compilation or an in-world Fabric GameTest run.
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

FIVE_BLOCKS = (
    'builders/block/BlockBuilder.java',
    'builders/block/BlockFiller.java',
    'builders/block/BlockQuarry.java',
    'factory/block/BlockMiningWell.java',
    'factory/block/BlockPump.java',
)
FIVE_TILES = (
    'builders/tile/TileBuilder.java',
    'builders/tile/TileFiller.java',
    'builders/tile/TileQuarry.java',
    'factory/tile/TileMiner.java',
    'factory/tile/TilePump.java',
)


class FabricRedstoneControlRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()
        cls.layout = target_layout('26.3-fabric', cls.props)

    def effective(self, relative: str) -> Path:
        path = resolve_effective_source(self.layout, self.props, relative)
        self.assertIsNotNone(path, relative)
        return path

    def java(self, relative: str) -> str:
        return self.effective('src/main/java/buildcraft/' + relative).read_text(encoding='utf-8')

    def asset(self, resource: str) -> dict:
        return json.loads(self.effective('src/main/resources/assets/' + resource).read_text(encoding='utf-8'))

    def test_five_fabric_blocks_own_model_selector(self):
        for relative in FIVE_BLOCKS:
            with self.subTest(block=relative):
                source = self.java(relative)
                self.assertIn('RedstoneModuleState.INSTALLED', source)
                self.assertIn('registerDefaultState', source)
                self.assertIn('createBlockStateDefinition(', source)
                self.assertIn('builder.add(RedstoneModuleState.INSTALLED)', source.replace('bs.add(', 'builder.add('))
                self.assertIn('false)', source)

    def test_normal_and_upgraded_model_variants_resolve(self):
        models = (
            ('buildcraftbuilders', 'builder', 'builder/main', 'builder/main_redstone'),
            ('buildcraftbuilders', 'filler', 'filler/main', 'filler/main_redstone'),
            ('buildcraftbuilders', 'quarry', 'quarry', 'quarry_redstone'),
            ('buildcraftfactory', 'mining_well', 'mining_well', 'mining_well_redstone'),
            ('buildcraftfactory', 'pump', 'pump', 'pump_redstone'),
        )
        for ns, block, normal, upgraded in models:
            with self.subTest(block=block):
                state = self.asset(f'{ns}/blockstates/{block}.json')
                if 'multipart' in state:
                    for facing in ('north', 'east', 'south', 'west'):
                        for installed, model in (('false', normal), ('true', upgraded)):
                            self.assertTrue(any(
                                entry.get('when', {}).get('facing') == facing
                                and entry.get('when', {}).get('redstone_module') == installed
                                and entry.get('apply', {}).get('model') == f'{ns}:block/{model}'
                                for entry in state['multipart']
                            ), (block, facing, installed))
                else:
                    self.assertEqual(set(state['variants']), {
                        f'waterlogged={wet},redstone_module={installed}'
                        for wet in ('false', 'true') for installed in ('false', 'true')
                    })
                for model in (normal, upgraded):
                    self.assertTrue(self.effective(f'src/main/resources/assets/{ns}/models/block/{model}.json').is_file())

    def test_fabric_pre_use_click_and_wrench_handler(self):
        handler = self.java('core/RedstoneModuleInteractions.java')
        bootstrap = self.java('core/CoreFabricInteractions.java')
        core = self.java('core/BCCore.java')
        for snippet in (
            'installRedstoneControlModule()', 'removeRedstoneControlModule()',
            'WrenchUtil.isWrench(held)', 'held.shrink(1)',
            'RedstoneModuleState.INSTALLED', 'player.getInventory().add(returned)',
            'WrenchUtil.wrenchUsed(player, hand, held, hit)',
            'InteractionResult.SUCCESS_SERVER',
        ):
            self.assertIn(snippet, handler)
        self.assertLess(
            bootstrap.index('RedstoneModuleInteractions.onUse('),
            bootstrap.index('ItemPaintbrush_BC8.shouldOverrideBlockUse(')
        )
        self.assertIn('UseBlockCallback.EVENT.register(', bootstrap)
        self.assertIn('CoreFabricInteractions.register()', core)

    def test_fabric_tile_persistence_and_loot(self):
        tile = self.java('lib/tile/TileBC_Neptune.java')
        for snippet in (
            'redstoneControlModuleInstalled = input.readBoolean(',
            'output.writeBoolean("redstoneControlModuleInstalled", redstoneControlModuleInstalled)',
            'private void syncRedstoneControlModuleBlockState()',
            'state.setValue(RedstoneModuleState.INSTALLED, redstoneControlModuleInstalled)',
            'syncRedstoneControlModuleBlockState();',
            'toDrop.add(new ItemStack(buildcraft.core.BCCoreItems.REDSTONE_CONTROL_MODULE.get()))',
            'level.hasNeighborSignal(worldPosition)',
        ):
            self.assertIn(snippet, tile)
        self.assertEqual(tile.count('public void addDrops(NonNullList<ItemStack> toDrop, int fortune)'), 1)

    def test_five_work_ticks_and_builder_robots_are_gated(self):
        for relative in FIVE_TILES:
            with self.subTest(tile=relative):
                self.assertIn('canWorkWithRedstoneControl()', self.java(relative))
        builder = self.java('builders/tile/TileBuilder.java')
        self.assertIn('&& canWorkWithRedstoneControl()\n', builder)
        pump = self.java('factory/tile/TilePump.java')
        self.assertIn('machineHost.canWorkWithRedstoneControl()', pump)

    def test_native_fabric_gametest_registered(self):
        name = 'FabricRedstoneControlModuleGameTests'
        gametest = ROOT / 'source-family-platforms/26.X/fabric/src/gametest/java/buildcraft/gametest' / (name + '.java')
        self.assertTrue(gametest.is_file())
        content = gametest.read_text(encoding='utf-8')
        self.assertIn('@GameTest', content)
        self.assertIn('helper.succeed()', content)
        for symbol in ('BCBuildersBlocks.BUILDER.get()', 'BCBuildersBlocks.FILLER.get()',
                       'BCBuildersBlocks.QUARRY.get()', 'BCFactoryBlocks.MINING_WELL_BLOCK.get()',
                       'BCFactoryBlocks.PUMP_BLOCK.get()'):
            self.assertIn(symbol, content)
        metadata = json.loads((ROOT / 'source-family-platforms/26.X/fabric/src/gametest/resources/fabric.mod.json')
                              .read_text(encoding='utf-8'))
        self.assertIn('buildcraft.gametest.' + name, metadata['entrypoints']['fabric-gametest'])


if __name__ == '__main__':
    unittest.main()
