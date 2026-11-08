#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))

from source_config import load_properties, target_layout
from source_layout import effective_source_files, _materialize_text_file
from minecraft_compat_fixture import parse_sources

TARGETS = (
    '1.19.2-forge', '1.20.1-forge',
    '1.21.1-neoforge', '1.21.11-neoforge',
    '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)
ENERGY = 'src/main/java/buildcraft/energy'


class FluidFogParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-fluid-fog-')
        cls.sources = {}
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            minecraft = props[f'target.{target}.deps.minecraft']
            downports = tuple(p for p in (layout.family_downport_root, layout.family_platform_downport_root) if p)
            root = Path(cls.temp.name) / target
            for relative, source in effective_source_files(layout, props, ENERGY).items():
                _materialize_text_file(
                    source, root / relative, logical_relative=relative,
                    minecraft=minecraft, family=layout.family, platform=layout.platform,
                    preprocess=True, native_source=any(source.is_relative_to(p) for p in downports),
                )
            cls.sources[target] = root / ENERGY

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def java(self, target: str, name: str) -> str:
        return (self.sources[target] / name).read_text(encoding='utf8')

    def test_all_targets_have_an_immersion_fog_color_and_distance(self):
        for target in TARGETS:
            with self.subTest(target=target):
                types = self.java(target, 'fluid/BCFluidType.java')
                registry = self.java(target, 'BCEnergyFluids.java')
                self.assertIn('int fogColor = BCFluidFog.color(data[4], data[5], heat)', registry)
                self.assertIn('float fogDistance = BCFluidFog.distance(boilAdjustedDensity)', registry)
                self.assertIn('tintColor, int fogColor, float fogDistance)', types)
                self.assertIn('this.fogDistance = fogDistance', types)
                self.assertIn('this.fogColor = fogColor', types)
                # Spout oil reuses crude-oil fluid type, and inherits its fog automatically.
                self.assertIn('OIL_TYPE.get(0)', registry)

    def test_legacy_fog_uses_near_and_far_shader_distance(self):
        for target in TARGETS[:3]:
            with self.subTest(target=target):
                types = self.java(target, 'fluid/BCFluidType.java')
                self.assertIn('public void modifyFogRender(', types)
                self.assertIn('RenderSystem.setShaderFogStart(0.0F)', types)
                self.assertIn('RenderSystem.setShaderFogEnd(fogDistance)', types)
                self.assertIn('BCFluidFog.channel(fogColor, 16)', types)
                self.assertIn('return new Vector3f(', types)
                self.assertIn('return null;', types)  # Preserve side-face overlay suppression.

    def test_modern_fog_uses_neoforge_fog_data_and_original_tint(self):
        for target in TARGETS[3:]:
            with self.subTest(target=target):
                extension = self.java(target, 'BCEnergyClientProxy.java')
                types = self.java(target, 'fluid/BCFluidType.java')
                self.assertIn('registerFluidClientExtensions', extension)
                self.assertIn('public void modifyFogRender(', extension)
                self.assertIn('fogData.environmentalStart = 0.0F;', extension)
                self.assertIn('fogData.renderDistanceStart = 0.0F;', extension)
                self.assertIn('fogData.environmentalEnd = type.getFogDistance()', extension)
                self.assertIn('fogData.renderDistanceEnd = type.getFogDistance()', extension)
                self.assertIn('type.getFogColor()', extension)
                self.assertIn('return tintColor;', types)
                self.assertNotIn('return new Vector3f(0.5f, 0.5f, 0.5f)', types)
                if target.startswith('26.'):
                    self.assertIn('fluidFogColor.set(red, green, blue, fluidFogColor.w);', extension)
                    self.assertNotIn('return new Vector4f(', extension)
                else:
                    self.assertIn('return new Vector4f(red, green, blue, fluidFogColor.w);', extension)

    def test_fog_palette_compiles_and_matches_bcu(self):
        with tempfile.TemporaryDirectory(prefix='bc-fog-compile-') as temp:
            root = Path(temp)
            fixture = root / 'FluidFogFixture.java'
            fixture.write_text('''import buildcraft.energy.fluid.BCFluidFog;
public class FluidFogFixture {
    static void ok(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        ok(BCFluidFog.color(0xFF505050, 0x050505, 0) == 0x2A2A2A);
        ok(BCFluidFog.color(0xFF505050, 0x050505, 1) == 0x3A3A3A);
        ok(BCFluidFog.color(0xFAF630, 0xE0D900, 0) == 0xEDE718);
        ok(BCFluidFog.distance(900) == 3.0F);
        ok(BCFluidFog.distance(-900) == 5.0F);
        ok(BCFluidFog.channel(0xFF102030, 8) == 32.0F / 255.0F);
    }
}
''', encoding='utf8')
            helper = ROOT / 'source-shared/src/main/java/buildcraft/energy/fluid/BCFluidFog.java'
            subprocess.run(['javac', '-d', str(root), str(helper), str(fixture)], check=True,
                           capture_output=True, text=True)
            subprocess.run(['java', '-cp', str(root), 'FluidFogFixture'], check=True,
                           capture_output=True, text=True)

    def test_changed_energy_sources_parse_in_all_generations(self):
        for target in TARGETS:
            with self.subTest(target=target):
                sources = self.sources[target]
                # Syntax check every mod generation's dedicated fluid type and registration.
                for name in ('BCEnergyFluids.java', 'fluid/BCFluidType.java'):
                    parsed = parse_sources([sources / name])
                    self.assertIn('0 errors', parsed)
                if target in TARGETS[3:]:
                    parsed = parse_sources([sources / 'BCEnergyClientProxy.java'])
                    self.assertIn('0 errors', parsed)


if __name__ == '__main__':
    unittest.main()
