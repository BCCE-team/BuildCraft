"""Static regression checks for Redux-style visual-only construction glow.

These protect family selection and lighting intent; they do not replace a Gradle
compile or an in-game night-time visual check.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class BuilderEmissiveVisualsTest(unittest.TestCase):
    def source(self, family: str, relative: str) -> str:
        return (ROOT / 'source-families' / family / 'src/main/java' / relative).read_text()

    def test_work_area_lasers_are_fullbright_but_not_other_lasers(self):
        for family in ('1.21.X', '26.X'):
            with self.subTest(family=family):
                box = self.source(family, 'buildcraft/lib/client/render/laser/LaserBoxRenderer.java')
                self.assertIn('RENDER_SCALE, true, false, 15, true', box)
                data_path = (ROOT / ('source-families/26.X/src/main/java' if family == '26.X' else 'source-shared/src/main/java') / 'buildcraft/lib/client/render/laser/LaserData_BC8.java')
                data = data_path.read_text()
                self.assertIn('minBlockLight', data)
                self.assertNotIn('setBlockAndUpdate(', box)

    def test_fullbright_status_lamps_for_all_three_builder_machines(self):
        for family in ('1.21.X', '26.X'):
            with self.subTest(family=family):
                lamps = self.source(family, 'buildcraft/builders/client/render/BuilderIndicatorLights.java')
                self.assertIn('lamp.center.lighti(15, 15);', lamps)
                for name in ('drawFiller', 'drawQuarry', 'drawArchitect'):
                    self.assertIn('void ' + name + '(', lamps)
                self.assertNotIn('level.setBlock(', lamps)
                self.assertNotIn('setLightEmission', lamps)

    def test_machine_renderers_submit_leds_in_local_coordinates(self):
        for family in ('1.21.X', '26.X'):
            for name, call in (('RenderFiller','drawFiller'), ('RenderQuarry','drawQuarry'),
                                ('RenderArchitectTable','drawArchitect')):
                with self.subTest(family=family, name=name):
                    text = self.source(family, 'buildcraft/builders/client/render/' + name + '.java')
                    self.assertIn('indicatorLights.' + call + '(', text)
                    self.assertIn('matrix.last().pose()', text)
        downport = (ROOT / 'source-downports/1.21.X/1.21.1/family/src/main/java/'
                    'buildcraft/builders/client/render/RenderFiller.java').read_text()
        self.assertIn('indicatorLights.drawFiller(', downport)

    def test_existing_pump_status_uses_valid_light_channels(self):
        pump = self.source('1.21.X','buildcraft/factory/client/render/RenderPump.java')
        self.assertIn('complete ? block : 15, complete ? sky : 15', pump)
        self.assertNotIn('lighti(Math.max(statusLight, block), sky)', pump)

if __name__ == '__main__':
    unittest.main()
