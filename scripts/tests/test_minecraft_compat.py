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
        self.assertEqual(95, current_sources.count('// bc-gametest-v2:'))
        self.assertTrue(registry.is_file())

        registry_text = registry.read_text(encoding='utf-8')
        self.assertEqual(95, registry_text.count('registry.register('))
        self.assertEqual(95, registry_text.count('event.registerTest('))
        self.assertIn('BuiltInRegistries.TEST_FUNCTION.key()', registry_text)
        self.assertIn('new FunctionGameTestInstance(', registry_text)
        self.assertIn('RegisterGameTestsEvent', registry_text)

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
