#!/usr/bin/env python3
"""All seven target-native sources must expose one consistent waterlogging contract."""
from __future__ import annotations

import json
import itertools
import re
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
            # Blockstate property order is not semantically significant. For example,
            # "waterlogged=false,redstone_module=true" and
            # "redstone_module=true,waterlogged=false" describe the same state.
            # Keep checking that every state has exactly one opposite waterlogging
            # variant, backed by the same model, without depending on key order.
            canonical_variants = {}
            for variant, model in variants.items():
                parts = [part.strip() for part in variant.split(',') if part.strip()]
                self.assertTrue(all(part.count('=') == 1 for part in parts), (path, variant))
                names = [part.split('=', 1)[0] for part in parts]
                self.assertTrue(all(names), (path, variant))
                self.assertEqual(len(names), len(set(names)), (path, variant))
                key = frozenset(parts)
                self.assertNotIn(key, canonical_variants, (path, variant, 'duplicate state'))
                canonical_variants[key] = model

            for variant, model in variants.items():
                properties = frozenset(part.strip() for part in variant.split(',') if part.strip())
                water_states = properties & {'waterlogged=false', 'waterlogged=true'}
                self.assertEqual(len(water_states), 1, (path, variant))
                other_water = ('waterlogged=true' if 'waterlogged=false' in properties
                               else 'waterlogged=false')
                other_state = (properties - water_states) | {other_water}
                self.assertIn(other_state, canonical_variants, (path, variant))
                self.assertEqual(model, canonical_variants[other_state], (path, variant))
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

    def test_pipe_dynamic_model_covers_all_blockstates(self):
        owner = 'buildcraft/transport/BCTransportModels.java'
        for target in (*TARGETS, '26.3-fabric'):
            with self.subTest(target=target):
                src = self.resolve(target, owner)
                if target.endswith('-forge'):
                    for wet in ('false', 'true'):
                        self.assertIn(f'putModel(event, "pipe_holder#waterlogged={wet}", ModelPipe.INSTANCE);', src)
                else:
                    self.assertIn('BCTransportBlocks.pipeHolder.get().getStateDefinition().getPossibleStates()', src)
                    if target == '1.21.1-neoforge':
                        self.assertIn('import net.minecraft.client.renderer.block.BlockModelShaper;', src)
                        self.assertRegex(src, r'for \(var state : BCTransportBlocks\.pipeHolder\.get\(\)\.getStateDefinition\(\)\.getPossibleStates\(\)\) \{\s*event\.getModels\(\)\.put\(BlockModelShaper\.stateToModelLocation\(state\), ModelPipe\.INSTANCE\);\s*\}')
                        self.assertNotIn('pipe_holder#waterlogged=', src)
                    else:
                        self.assertIn('event.blockStateModels().put(state, ModelPipeNative', src)

    def test_physics_mass_does_not_change_pipe_or_tank_json_model_selection(self):
        target = '1.21.1-neoforge'
        layout = target_layout(target, self.props)
        mass_state = self.resolve(target, 'buildcraft/compat/physics/SableMassState.java')
        balance = self.resolve(target, 'buildcraft/compat/physics/PhysicalMassBalance.java')
        self.assertIn('IntegerProperty.create("bc_physics_mass", 0, PhysicalMassBalance.MAX_STEP)', mass_state)
        maximum = int(re.search(r'MAX_STEP\s*=\s*(\d+)', balance).group(1))
        for ns, block, owner, model_for_state in (
            ('buildcrafttransport', 'pipe_holder', PIPE,
             lambda state: 'buildcrafttransport:block/pipe_holder'),
            ('buildcraftfactory', 'tank', TANK,
             lambda state: 'buildcraftfactory:block/tank' +
             ('_joined_below' if state['joined_below'] == 'true' else '')),
        ):
            with self.subTest(block=block):
                self.assertIn('SableMassState.MASS', self.resolve(target, owner))
                path = resolve_effective_source(layout, self.props,
                    f'src/main/resources/assets/{ns}/blockstates/{block}.json')
                self.assertIsNotNone(path)
                variants = json.loads(path.read_text(encoding='utf-8'))['variants']
                states = {'bc_physics_mass': [str(i) for i in range(maximum + 1)],
                          'waterlogged': ['false', 'true']}
                if block == 'tank':
                    states['joined_below'] = ['false', 'true']
                checked = set()
                for values in itertools.product(*states.values()):
                    state = dict(zip(states, values))
                    # Vanilla blockstate JSON variants are predicates: omitted
                    # properties (mass) are wildcards, not missing variants.
                    matches = [model for key, model in variants.items()
                               if all(state[name] == value for name, value in
                                      (part.split('=', 1) for part in key.split(',')))]
                    self.assertEqual(matches, [{'model': model_for_state(state)}], state)
                    checked.add(','.join(f'{name}={state[name]}' for name in sorted(state)))
                self.assertEqual((maximum + 1) * (4 if block == 'tank' else 2), len(checked))

    def test_loader_gametests_use_supported_registry_and_pump_names(self):
        # The first real 1.19.2 runGameTestServer compile must not be bypassed
        # by the lightweight Java syntax checks used in the parity suite.
        for target in TARGETS:
            with self.subTest(target=target):
                water = self.resolve(target, TEST)
                quarry_pump = self.resolve(target,
                    'src/gametest/java/buildcraft/gametest/SystemQuarryPumpGameTests.java')
                self.assertIn('BCFactoryBlocks.PUMP_BLOCK.get()', quarry_pump)
                self.assertNotIn('BCFactoryBlocks.PUMP.get()', quarry_pump)
                if target.endswith('-forge'):
                    # BuiltInRegistries was introduced after Minecraft 1.19.2;
                    # ForgeRegistries.BLOCKS is stable on both legacy targets.
                    self.assertIn('import net.minecraftforge.registries.ForgeRegistries;', water)
                    self.assertIn('for (Block block : ForgeRegistries.BLOCKS)', water)
                    self.assertIn('ForgeRegistries.BLOCKS.getKey(block)', water)
                    self.assertNotIn('BuiltInRegistries', water)

    def test_loader_gametests_are_materializable(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.resolve(target, TEST)
                self.assertIn('everyBuildCraftBlockExceptFluidsExposesWaterlogging', source)
                self.assertIn('frameAndTankRetainWaterWithoutLosingBlockIdentity', source)
                self.assertIn('defaultMachineStatesDoNotCreateWater', source)


if __name__ == '__main__':
    unittest.main()
