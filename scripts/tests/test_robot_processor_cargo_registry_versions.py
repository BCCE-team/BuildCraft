"""Guard the fluid-registry API split in Robot Processor cargo synchronization.

Minecraft 1.19.2 uses Registry.FLUID; later versions use BuiltInRegistries.FLUID.
This checks the *effective* source after the project's conditional preprocessor.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text

MENU = ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'
OLD_GUI = ROOT / 'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java'


class RobotProcessorCargoRegistryTests(unittest.TestCase):
    def test_fluid_registry_resolves_per_minecraft_version(self):
        for version, family, platform in (
            ('1.19.2', 'old', 'forge'),
            ('1.20.1', 'old', 'forge'),
            ('1.21.1', '1.21.X', 'neoforge'),
            ('1.21.11', '1.21.X', 'neoforge'),
            ('26.1.2', '26.X', 'neoforge'),
            ('26.2', '26.X', 'neoforge'),
            ('26.3', '26.X', 'neoforge'),
            ('26.3', '26.X', 'fabric'),
        ):
            with self.subTest(version=version, platform=platform):
                effective = preprocess_text(MENU.read_text(encoding='utf-8'),
                                            minecraft=version, family=family, platform=platform)
                self.assertNotIn('//?', effective)
                if version == '1.19.2':
                    self.assertIn('import net.minecraft.core.Registry;', effective)
                    self.assertIn('Registry.FLUID.getKey(fluid.getFluid())', effective)
                    self.assertNotIn('import net.minecraft.core.registries.BuiltInRegistries;', effective)
                    self.assertNotIn('var id = BuiltInRegistries.FLUID.getKey', effective)
                else:
                    self.assertIn('import net.minecraft.core.registries.BuiltInRegistries;', effective)
                    self.assertIn('BuiltInRegistries.FLUID.getKey(fluid.getFluid())', effective)
                    self.assertNotIn('Registry.FLUID.getKey(fluid.getFluid())',
                                     effective.replace('BuiltInRegistries.FLUID.getKey(fluid.getFluid())', ''))

    def test_1192_gui_uses_legacy_registry(self):
        code = OLD_GUI.read_text(encoding='utf-8')
        self.assertIn('import net.minecraft.core.Registry;', code)
        self.assertIn('Registry.FLUID.get(new ResourceLocation(id))', code)
        self.assertNotIn('BuiltInRegistries', code)


if __name__ == '__main__':
    unittest.main()
