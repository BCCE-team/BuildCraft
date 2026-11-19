"""Scrollbar rails and one-pixel cargo-thumb adjustment for the Robot Control Panel.

Source-level checks; Minecraft compilation/in-game verification is separate.
"""
from pathlib import Path
import unittest

from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[2]


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


if __name__ == '__main__':
    unittest.main()
