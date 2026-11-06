"""Legacy Forge working-area outline brightness parity.

The laser box is a client-only geometry object, never a world light source.
These are source-level regression checks; Gradle and in-game validation remain separate.
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

LASER_BOX = 'src/main/java/buildcraft/lib/client/render/laser/LaserBoxRenderer.java'
LASER_CONTEXT = 'src/main/java/buildcraft/lib/client/render/laser/LaserContext.java'


class OldForgeEmissiveFramesTest(unittest.TestCase):
    def test_old_laser_box_matches_modern_minimum_block_light(self):
        legacy = (ROOT / 'source-families/old' / LASER_BOX).read_text()
        modern = (ROOT / 'source-families/1.21.X' / LASER_BOX).read_text()
        expected = 'RENDER_SCALE, true, false, 15, true'
        self.assertIn(expected, modern)
        self.assertIn(expected, legacy)
        self.assertNotIn('RENDER_SCALE, true, false, 0, true', legacy)

    def test_every_old_forge_target_receives_bright_box_geometry(self):
        for version in ('1.19.2-forge', '1.20.1-forge'):
            with self.subTest(version=version):
                source = resolve_target_source(version, LASER_BOX)
                self.assertIn('source-families/old/', source.as_posix())
                box = read_materialized_java(version, LASER_BOX)
                ctx = read_materialized_java(version, LASER_CONTEXT)
                self.assertIn('RENDER_SCALE, true, false, 15, true', box)
                self.assertIn('this.minBlockLight = data.minBlockLight;', ctx)
                self.assertIn('computeLightmap(rx, ry, rz, minBlockLight)', ctx)

    def test_all_builder_work_area_renderers_use_laser_box(self):
        for filename in ('RenderBuilder', 'RenderFiller', 'RenderQuarry', 'RenderArchitectTable'):
            with self.subTest(renderer=filename):
                path = ROOT / 'source-families/old/src/main/java/buildcraft/builders/client/render' / (filename + '.java')
                self.assertIn('LaserBoxRenderer.renderLaserBoxDynamic(', path.read_text())

    def test_effect_is_render_only(self):
        legacy = (ROOT / 'source-families/old' / LASER_BOX).read_text()
        for forbidden in ('setBlockAndUpdate(', 'setLightEmission(', 'setBlock(', 'getLightEngine().checkBlock('):
            self.assertNotIn(forbidden, legacy)
        lamps = (ROOT / 'source-families/old/src/main/java/buildcraft/builders/client/render/BuilderIndicatorLights.java').read_text()
        self.assertIn('lamp.center.lighti(15, 15);', lamps)


if __name__ == '__main__':
    unittest.main()
