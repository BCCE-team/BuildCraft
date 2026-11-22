"""Regression checks for the Java APIs changed in Minecraft 26.3 Fabric/NeoForge.

These verify the effective code after Stonecutter branching and the 26.3
compatibility rewrite. In-game rendering and full Gradle assembly are separate.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts"))
from source_preprocessor import preprocess_text
from transforms.neoforge263 import upgrade_minecraft_263_apis

JAVA_ROOT = 'src/main/java/'
FILES = {
    'scheduler': 'source-shared/' + JAVA_ROOT + 'buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java',
    'tile': 'source-shared/' + JAVA_ROOT + 'buildcraft/robotics/tile/TileRobotProcessor.java',
    'menu': 'source-shared/' + JAVA_ROOT + 'buildcraft/robotics/container/ContainerRobotProcessor.java',
    'controller': 'source-shared/' + JAVA_ROOT + 'buildcraft/robotics/item/ItemRobotController.java',
    'zone': 'source-families/26.X/' + JAVA_ROOT + 'buildcraft/robotics/gui/GuiZonePlanner.java',
    'gui': 'source-families/26.X/' + JAVA_ROOT + 'buildcraft/robotics/gui/GuiRobotProcessor.java',
}


def effective_source(key: str, minecraft: str, platform: str) -> str:
    source = FILES[key]
    family = '26.X'
    code = preprocess_text((ROOT / source).read_text(encoding='utf-8'),
                           minecraft=minecraft, family=family, platform=platform,
                           source=source)
    return upgrade_minecraft_263_apis(code, minecraft=minecraft,
                                      relative=source.split(JAVA_ROOT, 1)[1])


class RobotProcessor263FabricCompatibilityTests(unittest.TestCase):
    def test_chunk_coordinates_use_accessors_in_all_26x_versions(self):
        for version, platform in [('26.1.2', 'neoforge'), ('26.2', 'neoforge'),
                                  ('26.3', 'fabric'), ('26.3', 'neoforge')]:
            with self.subTest(version=version, platform=platform):
                for file_key in ('scheduler', 'tile', 'zone'):
                    src = effective_source(file_key, version, platform)
                    self.assertRegex(src, r'return (?:pos|chunk)\.x\(\);')
                    self.assertRegex(src, r'return (?:pos|chunk)\.z\(\);')
                    self.assertNotRegex(src, r'\b(?:pos|chunk)\.x\s*[,;)]')
                    self.assertNotRegex(src, r'\b(?:pos|chunk)\.z\s*[,;)]')

    def test_overlay_messages_are_supported_by_all_26x_versions(self):
        for version, platform in [('26.1.2', 'neoforge'), ('26.2', 'neoforge'),
                                  ('26.3', 'fabric'), ('26.3', 'neoforge')]:
            with self.subTest(version=version, platform=platform):
                menu = effective_source('menu', version, platform)
                controller = effective_source('controller', version, platform)
                self.assertIn('sendOverlayMessage(', menu)
                self.assertIn('sendOverlayMessage(', controller)
                self.assertNotIn('displayClientMessage(', menu)
                self.assertNotIn('displayClientMessage(', controller)

    def test_robot_processor_extracts_tooltips_in_every_26x_version(self):
        for version, platform in [('26.1.2', 'neoforge'), ('26.2', 'neoforge'),
                                  ('26.3', 'fabric'), ('26.3', 'neoforge')]:
            with self.subTest(version=version, platform=platform):
                gui = effective_source('gui', version, platform)
                self.assertIn('protected void extractTooltip(', gui)
                self.assertIn('super.extractTooltip(graphics, mouseX, mouseY);', gui)
                self.assertNotIn('super.render(graphics, mouseX, mouseY, partialTicks)', gui)
                self.assertIn('RenderCompat.renderComponentTooltip(', gui)

    def test_263_shift_key_uses_input_adapter_without_glfw(self):
        for platform in ('fabric', 'neoforge'):
            with self.subTest(platform=platform):
                gui = effective_source('gui', '26.3', platform)
                self.assertIn('RenderCompat.inputKey(keyCode, scanCode).getName()', gui)
                self.assertIn('RenderCompat.hasInputShiftDown()', gui)
                self.assertIn('if (shiftPressed && !stationMode', gui)
                self.assertNotIn('GLFW.GLFW_MOD_SHIFT', gui)
                self.assertNotIn('InputConstants.KEY_LEFT_SHIFT', gui)
                self.assertNotIn('InputConstants.KEY_RIGHT_SHIFT', gui)
        old = effective_source('gui', '26.1.2', 'neoforge')
        self.assertIn('GLFW.GLFW_MOD_SHIFT', old)
        self.assertIn('protected void extractTooltip(', old)


if __name__ == '__main__':
    unittest.main()
