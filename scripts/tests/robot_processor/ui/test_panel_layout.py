"""Regression checks for the Control Panel's dedicated editor grid and explicit robot linking.

These are source/asset integration checks, not a substitute for running a game client.
"""
from pathlib import Path
import struct
import unittest

ROOT = Path(__file__).resolve().parents[4]
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
        # Updated background: 331x272 sprite in a 512x512 PNG.
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


# Consolidated related checks from test_scrollbars.py.
from pathlib import Path

import unittest

from scripts.tests.robot_processor.ui.test_panel_layout import ROBOT_SCREENS

class RobotProcessorScrollTracksTests(unittest.TestCase):
    def test_tracks_fit_inside_new_window_borders(self):
        # Scrollbar's 5x12 thumb must remain inside the shortened 7px rail
        # even at maximum scroll, not just when it is at its top position.
        for rel in ROBOT_SCREENS:
            with self.subTest(screen=rel):
                code = (ROOT / rel).read_text(encoding="utf-8")
                self.assertIn("ROBOT_SCROLL_TRAVEL = 93;", code)
                self.assertIn("CARGO_SCROLL_TRAVEL = 62;", code)
                self.assertIn("y < topPos + 22 + ROBOT_SCROLL_TRAVEL + 13;", code)
                self.assertIn("mouseY < topPos + CARGO_SCROLL_Y + CARGO_SCROLL_TRAVEL + 12;", code)
                # Drawing in [start, end) coords: robot track shortened 3 px,
                # cargo track 1 px, compared with the previous ZIP.
                robot_bottom = 22 + 93 + 13
                cargo_bottom = 191 + 62 + 13
                self.assertEqual(128, robot_bottom)
                self.assertEqual(266, cargo_bottom)
                self.assertEqual(3, (22 + 96 + 13) - robot_bottom)
                self.assertEqual(1, (191 + 63 + 13) - cargo_bottom)
                # Bottom-most 12px thumb is still wholly inside the rail.
                self.assertLessEqual(22 + 93 + 12, robot_bottom)
                self.assertLessEqual(191 + 62 + 12, cargo_bottom)

    def test_cargo_thumb_and_mouse_hitbox_move_together(self):
        for rel in ROBOT_SCREENS:
            with self.subTest(screen=rel):
                code = (ROOT / rel).read_text(encoding="utf-8")
                self.assertIn('CARGO_SCROLL_X = 235, CARGO_SCROLL_Y = 191;', code)
                self.assertIn('mouseX >= leftPos + CARGO_SCROLL_X - 1', code)
                self.assertIn('mouseY >= topPos + CARGO_SCROLL_Y', code)
                self.assertIn('(mouseY - topPos - CARGO_SCROLL_Y - 6.0) / CARGO_SCROLL_TRAVEL', code)
                self.assertIn('leftPos + CARGO_SCROLL_X, topPos + CARGO_SCROLL_Y + thumbY', code)
                self.assertNotIn('CARGO_SCROLL_X = 236, CARGO_SCROLL_Y = 192;', code)

    def test_both_tracks_draw_before_thumbs_for_all_conditions(self):
        for rel in ROBOT_SCREENS:
            with self.subTest(screen=rel):
                source = (ROOT / rel).read_text(encoding="utf-8")
                cargo = source[source.index('    private void renderCargo(PoseStack pose) {'):source.index('    private void renderCargoItem(', source.index('    private void renderCargo(PoseStack pose) {'))]
                self.assertLess(cargo.index('drawScrollTrack(pose, CARGO_SCROLL_X, CARGO_SCROLL_Y, CARGO_SCROLL_TRAVEL);'),
                                cargo.index('if (stationMode)'))
                self.assertIn('(count > CARGO_ROWS ? SCROLL_THUMB : SCROLL_DISABLED)', cargo)
                panel = source[source.index('    private void renderPanel(PoseStack pose) {'):source.index('    private void renderRobots(', source.index('    private void renderPanel(PoseStack pose) {'))]
                self.assertLess(panel.index('drawScrollTrack(pose, 94, 22, ROBOT_SCROLL_TRAVEL);'),
                                panel.index('(count > VISIBLE_ROWS ? SCROLL_THUMB : SCROLL_DISABLED)'))

    def test_recessed_track_has_continuous_shaded_strip(self):
        for rel in ROBOT_SCREENS:
            with self.subTest(screen=rel):
                text = (ROOT / rel).read_text(encoding='utf-8')
                self.assertIn('thumbX - 1, thumbY - 1, thumbX + 6, thumbY + travel + 13', text)
                self.assertIn('thumbX, thumbY, thumbX + 5, thumbY + travel + 12', text)
                self.assertIn('thumbX, thumbY, thumbX + 1, thumbY + travel + 12', text)
                self.assertIn('private static final GuiIcon SCROLL_DISABLED', text)
                self.assertIn('private static final GuiIcon SCROLL_THUMB', text)


if __name__ == "__main__":
    unittest.main()
