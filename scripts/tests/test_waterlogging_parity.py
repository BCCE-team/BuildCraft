#!/usr/bin/env python3
"""All seven target-native sources must expose one consistent waterlogging contract."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, target_layout, resolve_effective_source, _materialize_text_file

TARGETS = (
    '1.19.2-forge', '1.20.1-forge',
    '1.21.1-neoforge', '1.21.11-neoforge',
    '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)
BASE = 'buildcraft/lib/block/BlockBCBase_Neptune.java'
PIPE = 'buildcraft/transport/block/BlockPipeHolder.java'
COLLISION = 'buildcraft/builders/block/BlockQuarryDrillCollision.java'
FRAME = 'buildcraft/builders/block/BlockFrame.java'
MARKER = 'buildcraft/lib/block/BlockMarkerBase.java'
QUARRY = 'buildcraft/builders/tile/TileQuarry.java'
COLLISION_TILE = 'buildcraft/builders/tile/TileQuarryDrillCollision.java'
CHUTE = 'buildcraft/factory/block/BlockChute.java'
EXCHANGER = 'buildcraft/factory/block/BlockHeatExchange.java'
TANK = 'buildcraft/factory/block/BlockTank.java'
TEST = 'src/gametest/java/buildcraft/gametest/WaterloggingGameTests.java'


class WaterloggingParity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()

    def resolve(self, target: str, name: str) -> str:
        layout = target_layout(target, self.props)
        logical = name if name.startswith('src/') else 'src/main/java/' + name
        source = resolve_effective_source(layout, self.props, logical)
        self.assertIsNotNone(source, (target, logical))
        with tempfile.TemporaryDirectory(prefix='bcce-waterlogged-') as temp:
            output = Path(temp) / logical
            minecraft = self.props[f'target.{target}.deps.minecraft']
            downport_roots = tuple(root for root in (
                layout.family_downport_root, layout.family_platform_downport_root
            ) if root is not None)
            _materialize_text_file(
                source, output, logical_relative=logical, minecraft=minecraft,
                family=layout.family, platform=layout.platform, preprocess=True,
                native_source=any(source.is_relative_to(root) for root in downport_roots),
            )
            return output.read_text(encoding='utf-8')

    def test_all_targets_have_one_water_state_and_water_ticks(self):
        for target in TARGETS:
            with self.subTest(target=target):
                base = self.resolve(target, BASE)
                pipe = self.resolve(target, PIPE)
                collision = self.resolve(target, COLLISION)
                self.assertIn('implements SimpleWaterloggedBlock', base)
                self.assertEqual(1, base.count('bs.add(WATERLOGGED);'))
                self.assertIn('getFluidState(pos).getType() == Fluids.WATER', base)
                self.assertIn('getFluidState(BlockState state)', base)
                self.assertIn('scheduleTick(pos, Fluids.WATER,', base)
                self.assertNotIn('builder.add(WATERLOGGED);', pipe)
                self.assertIn('implements EntityBlock, SimpleWaterloggedBlock', collision)
                self.assertIn('builder.add(MASK, WATERLOGGED);', collision)
                self.assertIn('scheduleTick(pos, Fluids.WATER,', collision)
                if target == '1.21.1-neoforge' or target.endswith('-forge'):
                    self.assertIn('public BlockState updateShape(BlockState state, Direction direction,', collision)
                    self.assertIn('LevelAccessor world, BlockPos pos, BlockPos neighbourPos', collision)
                    self.assertNotIn('ScheduledTickAccess', collision)
                else:
                    self.assertIn('protected BlockState updateShape(BlockState state, LevelReader world,', collision)
                    self.assertIn('ScheduledTickAccess tickAccess', collision)
                    self.assertNotIn('LevelAccessor world, BlockPos pos, BlockPos neighbourPos', collision)
                if target.endswith('-forge'):
                    legacy_engine = self.resolve(target, 'buildcraft/core/block/BlockEngine.java')
                    self.assertIn('implements EntityBlock, SimpleWaterloggedBlock', legacy_engine)
                if target == '1.21.1-neoforge' or target.endswith('-forge'):
                    self.assertIn('LevelAccessor world, BlockPos pos, BlockPos neighbourPos', base)
                else:
                    self.assertIn('ScheduledTickAccess tickAccess', base)

    def test_placement_and_removal_retain_water(self):
        for target in TARGETS:
            with self.subTest(target=target):
                frame = self.resolve(target, FRAME)
                self.assertIn('super.getStateForPlacement(bpc)', frame)
                self.assertNotIn('BlockState state = defaultBlockState();', frame)
                self.assertIn('getFluidState().createLegacyBlock()', self.resolve(target, MARKER))
                quarry = self.resolve(target, QUARRY)
                self.assertIn('.setValue(BlockQuarryDrillCollision.WATERLOGGED,', quarry)
                self.assertIn('BlockBCBase_Neptune.WATERLOGGED', quarry)
                self.assertIn('state.getFluidState().createLegacyBlock()', quarry)
                self.assertIn('getFluidState().createLegacyBlock()', self.resolve(target, COLLISION_TILE))
                self.assertIn('super.updateShape(', self.resolve(target, CHUTE))
                self.assertIn('super.updateShape(', self.resolve(target, EXCHANGER))
                self.assertIn('super.getStateForPlacement(bpc)', self.resolve(target, TANK))

    def test_all_nonfluid_variant_models_have_both_water_states(self):
        # A new BlockState property must be reflected in variant-based JSON;
        # otherwise even the dry block will render as a missing model.
        root = ROOT / 'source-shared/src/main/resources/assets'
        checked = 0
        for path in root.glob('*/blockstates/*.json'):
            if path.parent.parent.name == 'buildcraftenergy' and path.stem != 'mj_dynamo':
                continue  # Actual oil/fuel blocks are not waterloggable.
            data = json.loads(path.read_text(encoding='utf-8'))
            variants = data.get('variants')
            if variants is None:
                continue  # Multipart definitions ignore extra state properties.
            for variant, model in variants.items():
                properties = [part for part in variant.split(',') if part]
                self.assertTrue(any(part == 'waterlogged=false' for part in properties)
                                or any(part == 'waterlogged=true' for part in properties), path)
                other = [part for part in properties if not part.startswith('waterlogged=')]
                water = 'waterlogged=true' if 'waterlogged=false' in properties else 'waterlogged=false'
                other_variant = ','.join(other + [water])
                self.assertIn(other_variant, variants, (path, variant))
                self.assertEqual(model, variants[other_variant], (path, variant))
                checked += 1
        self.assertGreaterEqual(checked, 40)

    def test_subclasses_do_not_reset_dry_waterlogging_defaults(self):
        # StateDefinition.any() bypasses the base class constructor's explicit
        # WATERLOGGED=false. A derived class must refine defaultBlockState().
        classes = (
            'buildcraft/core/block/BlockEngine_BC8.java',
            'buildcraft/core/block/BlockSpring.java',
            'buildcraft/factory/block/BlockTank.java',
            'buildcraft/factory/block/BlockWaterGel.java',
            'buildcraft/factory/block/BlockChute.java',
            'buildcraft/factory/block/BlockHeatExchange.java',
            'buildcraft/builders/block/BlockFrame.java',
            'buildcraft/builders/block/BlockBuilder.java',
            'buildcraft/builders/block/BlockArchitectTable.java',
            'buildcraft/lib/block/BlockMarkerBase.java',
        )
        for target in TARGETS:
            for name in classes:
                with self.subTest(target=target, name=name):
                    source = self.resolve(target, name)
                    self.assertIn('defaultBlockState()', source)
                    self.assertNotIn('stateDefinition.any()', source,
                                     'Derived default state must preserve WATERLOGGED=false')

    def test_engine_custom_models_cover_dry_and_wet_blockstates(self):
        # Legacy renderers replace baked blockstate models with ModelEngine.
        # Each additional BlockState property changes the ModelResourceLocation,
        # so an old type-only key leaves the engine's static texture probe on screen.
        engine_json = json.loads((ROOT / 'source-shared/src/main/resources/assets/buildcraftcore/blockstates/engine.json').read_text(encoding='utf-8'))
        dynamo_json = json.loads((ROOT / 'source-shared/src/main/resources/assets/buildcraftenergy/blockstates/mj_dynamo.json').read_text(encoding='utf-8'))
        for target, owner in (
            ('1.19.2-forge', 'buildcraft/core/BCCore.java'),
            ('1.20.1-forge', 'buildcraft/core/BCCore.java'),
            ('1.21.1-neoforge', 'buildcraft/core/client/BCCoreClientModEvents.java'),
        ):
            with self.subTest(target=target):
                src = self.resolve(target, owner)
                self.assertIn('for (boolean waterlogged : new boolean[] { false, true })', src)
                lines = [line.strip() for line in src.splitlines()
                         if 'event.getModels().put(new ModelResourceLocation(' in line and 'new ModelEngine(' in line]
                for name in ('wood', 'creative', 'stone', 'iron', 'fe'):
                    key = 'type=' + name
                    for wet in ('false', 'true'):
                        self.assertIn(key + ',waterlogged=' + wet, engine_json['variants'])
                    matches = [line for line in lines if (key + ',waterlogged="') in line]
                    self.assertEqual(1, len(matches), (target, name))
                    self.assertIn('+ waterlogged', matches[0])
                self.assertEqual(6, len(lines), (target, lines))
                for wet in ('false', 'true'):
                    self.assertIn('waterlogged=' + wet, dynamo_json['variants'])
                self.assertTrue(any('waterlogged=" + waterlogged' in line for line in lines), target)

    def test_loader_gametests_are_materializable(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.resolve(target, TEST)
                self.assertIn('everyBuildCraftBlockExceptFluidsExposesWaterlogging', source)
                self.assertIn('frameAndTankRetainWaterWithoutLosingBlockIdentity', source)
                self.assertIn('defaultMachineStatesDoNotCreateWater', source)


if __name__ == '__main__':
    unittest.main()
