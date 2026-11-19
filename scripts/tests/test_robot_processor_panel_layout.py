"""Regression checks for the Control Panel's dedicated editor grid and explicit robot linking.

These are source/asset integration checks, not a substitute for running a game client.
"""
from pathlib import Path
import struct
import unittest

ROOT = Path(__file__).resolve().parents[2]
ZONE_SOURCES = [
    'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'source-families/1.21.X/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'version-src/26.1.2-neoforge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'source-families/26.X/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
]
ROBOT_SCREENS = [
    'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
    'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
    'source-families/1.21.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
    'source-families/26.X/src/main/java/buildcraft/robotics/gui/GuiRobotProcessor.java',
]


class RobotProcessorPanelLayout(unittest.TestCase):
    def test_editor_palette_matches_exact_4x5_artwork_grid(self):
        # Updated background: 323x272 sprite in a 512x512 PNG.
        path = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png'
        png = path.read_bytes()
        self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
        self.assertEqual(struct.unpack('>II', png[16:24]), (512, 512))
        for source in ZONE_SOURCES:
            with self.subTest(source=source):
                code = (ROOT / source).read_text(encoding='utf-8')
                self.assertIn('PROCESSOR_SHAPE_X = {264, 282, 300}', code)
                self.assertIn('return processorMode ? 246 : ERASER_X;', code)
                self.assertIn('return processorMode ? 177 : SHAPE_Y;', code)
                self.assertIn('return processorMode ? 246 : COLOUR_X;', code)
                self.assertIn('return processorMode ? 195 : COLOUR_Y;', code)
                self.assertIn('return processorMode ? 18 : COLOUR_STEP;', code)
                self.assertIn('return processorMode ? PROCESSOR_SHAPE_X : SHAPE_X;', code)
                # Clicking must continue to use the same palette coordinates as rendering.
                self.assertIn('getColourSlotAt(', code)
                self.assertIn('insideRelativeButton(mouseX, mouseY, eraserX(), shapeY()', code)

    def test_map_import_slot_absent_and_palette_is_not_machine_inventory(self):
        code = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        self.assertIn('replaceSlot(2, new SlotBase(', code)
        self.assertIn('mayPlace(ItemStack stack) { return false; }', code)
        self.assertIn('mayPickup(Player player) { return false; }', code)
        self.assertNotIn('relocateSlot(2, 225, 25)', code)
        self.assertNotIn('new SlotBase(storage,', code)
        self.assertEqual(code.count('addSlot('), 0, 'The 4x5 editor controls are not item slots')
        self.assertIn('8 + col * 18, 191 + row * 18', code)
        self.assertIn('8 + col * 18, 249', code)

    def test_only_explicitly_linked_robots_are_visible_and_recallable(self):
        menu = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        tile = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java').read_text()
        self.assertNotIn('getLoadedRobots()', menu)
        self.assertIn('for (long robotId : processor.getLinkedRobotIds())', menu)
        self.assertIn('if (processor == null || processor.getLinkedRobotIds().isEmpty())', menu)
        self.assertIn('!processor.isRobotLinked(robotId)', menu)
        self.assertIn('private final Set<Long> linkedRobotIds = new LinkedHashSet<>();', tile)
        self.assertNotIn('processor.linkRobot(', menu)
        self.assertIn('unlinkRobot(', menu)

    def test_text_is_width_limited_and_unshadowed(self):
        for source in ROBOT_SCREENS:
            with self.subTest(source=source):
                code = (ROOT / source).read_text()
                self.assertIn('private String fitText(', code)
                self.assertIn('font.width(text)', code)
                self.assertIn('fitText(text, maxWidth)', code)
                self.assertIn('topPos + y, color, false)', code)
                self.assertIn('HOME_ICON_FORCE', code)
                self.assertIn('UNLINK_ICON', code)


if __name__ == '__main__':
    unittest.main()
