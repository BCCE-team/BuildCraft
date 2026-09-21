#!/usr/bin/env python3
"""Real internal Java boundaries against offline doubles, not Minecraft/Gradle tests."""
from __future__ import annotations
from pathlib import Path
import re
import sys
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, materialize_target
from transforms.java_symbols import downport_symbols
from minecraft_compat_fixture import run, parse_sources
from minecraft_compat_probes import probe


class SymbolDownports(unittest.TestCase):
    def old(self, text: str, relative: str = 'Example.java') -> str:
        return downport_symbols(text, minecraft='1.21.1', relative=relative)

    def test_whitelisted_import_and_type_only_outside_literals(self):
        text = 'import net.minecraft.resources.Identifier;\nclass A { Identifier value; String s="Identifier"; char c=\'I\'; /* Identifier */ }\n// Identifier\n'
        result = self.old(text)
        self.assertIn('import net.minecraft.resources.ResourceLocation;', result)
        self.assertIn('ResourceLocation value;', result)
        self.assertIn('"Identifier"', result)
        self.assertIn('/* Identifier */', result)
        self.assertIn('// Identifier', result)
        self.assertEqual(result, self.old(result))

    def test_opaque_text_blocks_and_comments_cannot_enable_a_transform(self):
        for text in (
            '/*\nimport net.minecraft.resources.Identifier;\n*/\nclass Identifier {}',
            'String s="""\nimport net.minecraft.resources.Identifier;\n""";\nclass Identifier {}',
        ):
            self.assertEqual(text, self.old(text))

    def test_other_namespace_and_new_api_are_untouched(self):
        text = 'import example.Identifier;\nclass A {Identifier value;}'
        self.assertEqual(text, self.old(text))
        text = text.replace('example.Identifier', 'net.minecraft.resources.Identifier')
        self.assertEqual(text, downport_symbols(text, minecraft='1.21.11', relative='A.java'))
        self.assertEqual(text, self.old(text, 'readme.txt'))

    def test_render_type_package_is_mechanical(self):
        text = 'import net.minecraft.client.renderer.rendertype.RenderType;\nclass A {RenderType value;}'
        self.assertEqual('import net.minecraft.client.renderer.RenderType;\nclass A {RenderType value;}', self.old(text))


class MinecraftBoundaries(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bc-minecraft-boundaries-')
        cls.roots = {}
        cls.target_roots = {}
        config = load_properties()
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            root = Path(cls.temporary.name) / target
            materialize_target(target, root, config)
            cls.target_roots[target] = root
            cls.roots[target] = root / 'src/main/java'

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_all_modern_effective_java_parses(self):
        print(parse_sources(list(self.roots.values())))

    def test_real_java_boundaries_on_both_modern_api_shapes(self):
        for target, java in self.roots.items():
            with self.subTest(target=target):
                print(run(java, current=target.startswith('1.21.11'), probe=probe(target.startswith('1.21.11'))))

    def test_only_boundary_owns_vanilla_machine_persistence_callbacks(self):
        for target, root in self.roots.items():
            for path in (root / 'buildcraft').rglob('*.java'):
                text = path.read_text(encoding='utf-8')
                methods = re.findall(r'\bvoid\s+(loadAdditional|saveAdditional)\s*\(', text)
                if methods:
                    self.assertEqual('BCBlockEntity.java', path.name, f'{target}: {path}')
            base = (root / 'buildcraft/lib/compat/minecraft/persistence/BCBlockEntity.java').read_text()
            self.assertIn('protected final void loadAdditional(', base)
            self.assertIn('protected final void saveAdditional(', base)
            self.assertIn('readCommonData(', base)
            self.assertIn('writeCommonData(', base)
            tile = (root / 'buildcraft/lib/tile/TileBC_Neptune.java').read_text()
            self.assertNotIn('input.readIntArray("d").orElse', tile)

    def test_schema_exceptions_remain_explicit(self):
        java = self.roots['1.21.11-neoforge'] / 'buildcraft'
        base = (java / 'lib/compat/minecraft/persistence/BCBlockEntity.java').read_text()
        self.assertIn('"bc_legacy"', base)
        self.assertIn('input.lookup()', base)
        for rel in ('transport/tile/TilePipeHolder.java', 'energy/tile/TileSpringOil.java', 'builders/tile/TileQuarryDrillCollision.java'):
            self.assertIn('storesMachineDataAtRoot() { return true; }', (java / rel).read_text(), rel)
        pipe = (java / 'transport/tile/TilePipeHolder.java').read_text()
        for key in ('"pipe"', '"plugs"', '"wireManager"', '"redstone"'):
            self.assertIn(key, pipe)
        self.assertIn('requiresPersistenceRegistries() { return false; }', pipe)
        self.assertIn('output.hasRegistries() ? output.registries() : previousRegistries', pipe)
        self.assertIn('unknownData.copy()', pipe)
        self.assertIn('serializationRegistries = previousRegistries', pipe)

    def test_container_screen_texture_id_matches_modern_generation(self):
        old = (self.roots['1.21.1-neoforge'] / 'buildcraft/lib/gui/ContainerScreenBase.java').read_text()
        current = (self.roots['1.21.11-neoforge'] / 'buildcraft/lib/gui/ContainerScreenBase.java').read_text()
        self.assertIn('import net.minecraft.resources.ResourceLocation;', old)
        self.assertNotIn('import net.minecraft.resources.Identifier;', old)
        self.assertIn('ResourceLocation TEXTURE_BASE', old)
        self.assertIn('import net.minecraft.resources.Identifier;', current)
        self.assertNotIn('import net.minecraft.resources.ResourceLocation;', current)
        self.assertIn('Identifier TEXTURE_BASE', current)

    def test_gametest_registration_matches_modern_api_generation(self):
        old_root = self.target_roots['1.21.1-neoforge'] / 'src/gametest/java'
        current_root = self.target_roots['1.21.11-neoforge'] / 'src/gametest/java'

        old_sources = '\n'.join(path.read_text(encoding='utf-8') for path in old_root.rglob('*.java'))
        current_sources = '\n'.join(path.read_text(encoding='utf-8') for path in current_root.rglob('*.java'))
        registry = current_root / 'buildcraft/gametest/BuildCraftGeneratedGameTests.java'

        self.assertIn('import net.minecraft.gametest.framework.GameTest;', old_sources)
        self.assertIn('@GameTestHolder(', old_sources)
        self.assertFalse((old_root / 'buildcraft/gametest/BuildCraftGeneratedGameTests.java').exists())

        self.assertNotIn('import net.minecraft.gametest.framework.GameTest;', current_sources)
        self.assertNotIn('@GameTestHolder(', current_sources)
        self.assertNotIn('@PrefixGameTestTemplate(', current_sources)
        self.assertNotIn('NbtCompat.getInt(GameTestCompat,', current_sources)
        self.assertIn('GameTestCompat.readInt(', current_sources)
        self.assertIn('GameTestCompat.readString(', old_sources)
        self.assertIn('GameTestCompat.readString(', current_sources)
        self.assertEqual(95, current_sources.count('// bc-gametest-v2:'))
        self.assertTrue(registry.is_file())

        registry_text = registry.read_text(encoding='utf-8')
        self.assertEqual(95, registry_text.count('registry.register('))
        self.assertEqual(95, registry_text.count('event.registerTest('))
        self.assertEqual(95, registry_text.count('helper -> invoke(helper'))
        self.assertIn('private interface CheckedGameTest', registry_text)
        self.assertIn('BuiltInRegistries.TEST_FUNCTION.key()', registry_text)
        self.assertIn('new FunctionGameTestInstance(', registry_text)
        self.assertIn('RegisterGameTestsEvent', registry_text)

        old_compat = (old_root / 'buildcraft/gametest/GameTestCompat.java').read_text(encoding='utf-8')
        current_compat = (current_root / 'buildcraft/gametest/GameTestCompat.java').read_text(encoding='utf-8')
        for compat in (old_compat, current_compat):
            self.assertIn('getBlockEntity(helper.absolutePos(pos))', compat)
            self.assertIn('invokeFirst(profile, "id", "getId")', compat)
            self.assertIn('Class.forName("net.minecraft.world.level.storage.TagValueOutput")', compat)
            self.assertIn('Class.forName("net.minecraft.world.item.crafting.display.SlotDisplayContext")', compat)
            self.assertIn('saveBlockEntity(', compat)
            self.assertIn('loadBlockEntity(', compat)
            self.assertIn('findDeclaredCompatibleMethod(', compat)
            self.assertIn('public static String readString(', compat)
        self.assertTrue((ROOT / 'source-families/modern/src/gametest/java/buildcraft/gametest/GameTestCompat.java').is_file())
        self.assertFalse((ROOT / 'source-platforms/neoforge/src/gametest/java/buildcraft/gametest/GameTestCompat.java').exists())

    def test_modern_fluid_water_guards_survive_12111_api_changes(self):
        current_root = self.target_roots['1.21.11-neoforge'] / 'src/main/java'
        fluid = (current_root / 'buildcraft/lib/fluid/BCFluid.java').read_text(encoding='utf-8')
        pump = (current_root / 'buildcraft/factory/tile/TilePump.java').read_text(encoding='utf-8')

        self.assertIn('protected void spreadTo(LevelAccessor level', fluid)
        self.assertIn('isWater(level.getFluidState(pos))', fluid)
        self.assertIn('fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER', fluid)
        self.assertIn('scanForInfiniteWater = !BCCoreConfig.pumpsConsumeWater && isWater(scanFluid);', pump)
        self.assertIn('isWaterSource(neighbour)', pump)
        self.assertIn('fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER', pump)
        self.assertIn('fluid == Fluids.WATER || (state.isSource() && isWater(state))', pump)
        self.assertIn('if (!level.hasChunkAt(neighbourPos)) {\n                continue;', pump)
        self.assertNotIn('if (!level.hasChunkAt(neighbourPos)) {\n                return false;', pump)
        self.assertIn('&& isWater(drain.getFluid());', pump)

    def test_cross_target_safety_contracts_survive_modern_materialization(self):
        current = self.roots['1.21.11-neoforge'] / 'buildcraft'
        old = self.roots['1.21.1-neoforge'] / 'buildcraft'

        for root in (old, current):
            drops = (root / 'lib/block/BlockBCTile_Neptune.java').read_text(encoding='utf-8')
            self.assertIn('getCloneItemStack(', drops)

            volume = (root / 'core/marker/volume/WorldSavedDataVolumeBoxes.java').read_text(encoding='utf-8')
            get_method = volume[volume.index('public static WorldSavedDataVolumeBoxes get(Level world)'):]
            self.assertIn('getDataStorage()', get_method)
            self.assertNotIn('return new WorldSavedDataVolumeBoxes(world);', get_method.split('\n    }', 1)[0])

            energy = (root / 'energy/BCEnergy.java').read_text(encoding='utf-8')
            advancement = energy[energy.index('private static boolean hasAdvancement('):]
            advancement = advancement[:advancement.index('\n    }') + 6]
            self.assertIn('getAdvancements()', advancement)
            self.assertIn('getOrStartProgress', advancement)
            self.assertIn('.isDone()', advancement)

    def test_forge_and_neoforge_pump_use_the_same_infinite_water_rules(self):
        forge = (ROOT / 'source-platforms/forge/src/main/java/buildcraft/factory/tile/TilePump.java').read_text(encoding='utf-8')
        neo = (ROOT / 'source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TilePump.java').read_text(encoding='utf-8')
        for pump in (forge, neo):
            self.assertIn('scanForInfiniteWater = !BCCoreConfig.pumpsConsumeWater && isWater(scanFluid);', pump)
            self.assertIn('if (!level.hasChunkAt(neighbourPos)) {\n                continue;', pump)
            self.assertNotIn('if (!level.hasChunkAt(neighbourPos)) {\n                return false;', pump)
            self.assertIn('state.is(FluidTags.WATER)', pump)
            self.assertIn('isWaterSource(neighbour)', pump)

    def test_pump_infinite_water_fixture_stays_inside_empty3x3x3(self):
        suite = (
            ROOT
            / 'source-platforms/neoforge/src/gametest/java/buildcraft/gametest/BuildCraftLogicGameTests.java'
        ).read_text(encoding='utf-8')
        self.assertIn('BlockPos pumpPos = new BlockPos(1, 2, 1);', suite)
        self.assertIn('BlockPos waterPos = new BlockPos(1, 1, 1);', suite)
        self.assertNotIn('BlockPos pumpPos = new BlockPos(1, 4, 1);', suite)
        self.assertNotIn('BlockPos waterPos = new BlockPos(1, 2, 1);', suite)

    def test_12111_client_runtime_hooks_are_isolated_from_common_event_owner(self):
        current_root = self.target_roots['1.21.11-neoforge'] / 'src/main/java'
        events = (current_root / 'buildcraft/lib/BCLibEventDist.java').read_text(encoding='utf-8')

        self.assertIn('ClientGame.registerGameplayEvents();', events)
        self.assertIn('PlatformClientEvents.login(ClientGame::onConnectToServer);', events)
        self.assertIn('PlatformClientEvents.logout(ClientGame::onDisconnectFromServer);', events)
        self.assertIn('PlatformClientEvents.tick(BCEvents.Phase.END, ClientGame::clientTick);', events)
        self.assertNotIn('PlatformClientEvents.login(BCLibEventDist::onConnectToServer);', events)
        self.assertNotIn('PlatformClientEvents.logout(BCLibEventDist::onDisconnectFromServer);', events)
        self.assertNotIn('PlatformClientEvents.tick(BCEvents.Phase.END, BCLibEventDist::clientTick);', events)

    def test_legacy_pipe_targeting_uses_block_reach(self):
        pipe = (ROOT / 'version-src/1.19.2-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text(encoding='utf-8')
        shape = pipe[pipe.index('public VoxelShape getShape('):]
        self.assertIn('player.getReachDistance()', shape)
        self.assertNotIn('player.getAttackRange()', shape)

    def test_smoke_scripts_read_canonical_target_metadata(self):
        for script in ('ci-server-smoke.sh', 'ci-client-smoke.sh'):
            smoke = (ROOT / 'scripts' / script).read_text(encoding='utf-8')
            self.assertIn('target_config="${repo_root}/build-config/targets.properties"', smoke, script)
            self.assertNotIn('target_config="${build_root}/targets.properties"', smoke, script)

    def test_legacy_fluid_widget_keeps_client_renderer_off_dedicated_server(self):
        widget = (ROOT / 'source-families/legacy/src/main/java/buildcraft/lib/gui/widget/WidgetFluidTank.java').read_text(encoding='utf-8')
        client = (ROOT / 'source-families/legacy/src/main/java/buildcraft/lib/gui/widget/GuiElementFluidTank.java').read_text(encoding='utf-8')
        self.assertNotIn('IGuiElement', widget)
        self.assertNotIn('BuildCraftGui', widget)
        self.assertNotIn('GuiElementSimple', widget)
        self.assertIn('class GuiElementFluidTank extends GuiElementSimple', client)
        self.assertIn('widget.sendClick();', client)
        self.assertIn('widget.getTank()', client)

    def test_new_facades_are_loader_neutral(self):
        root = ROOT / 'source-families/modern/src/main/java/buildcraft/lib/compat/minecraft'
        for path in root.rglob('*.java'):
            text = path.read_text()
            self.assertNotRegex(text, r'\b(?:net\.neoforged|net\.minecraftforge|net\.fabricmc)\.', str(path))
            self.assertNotRegex(text, r'(?m)^package (?:net\.|com\.mojang)', str(path))

    def test_no_class_specific_bootstrap_or_duplicate_target_renderers(self):
        self.assertFalse((ROOT / 'scripts/transforms/bootstrap_12111.py').exists())
        compat = (ROOT / 'scripts/transforms/java_compat.py').read_text()
        self.assertNotRegex(compat, r'buildcraft/[^\"\']+\.java')
        self.assertEqual(1, compat.count('relative.endswith(".java")'))
        overlay = ROOT / 'version-src/1.21.11-neoforge/src/main/java/buildcraft'
        for rel in ('factory/client/render/RenderPump.java', 'factory/client/render/RenderMiningWell.java', 'lib/gui/ContainerScreenBase.java'):
            self.assertFalse((overlay / rel).exists(), rel)


if __name__ == '__main__':
    unittest.main(verbosity=2)
