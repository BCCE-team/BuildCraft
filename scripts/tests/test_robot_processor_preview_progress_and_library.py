"""Robot Processor preview, measurable work progress, and linked-entry list checks.

Source-level regression tests, not a substitute for compiling with Minecraft libraries.
"""
import unittest
from pathlib import Path

from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[2]
AI = ROOT / 'source-shared/src/main/java/buildcraft/robotics/internal/legacy/robots/AIRobot.java'
ATTACK = ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotAttack.java'
HARVEST = ROOT / 'source-families/old/src/main/java/buildcraft/robotics/ai/AIRobotHarvest.java'
BREAKS = [
    ROOT / 'source-families/old/src/main/java/buildcraft/robotics/ai/AIRobotBreak.java',
    ROOT / 'source-families/1.21.X/src/main/java/buildcraft/robotics/ai/AIRobotBreak.java',
]
MENU = ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'


class RobotProcessorPreviewProgressTests(unittest.TestCase):
    def test_execution_phase_only_and_measurable_actions(self):
        self.assertIn('public int getExecutionProgress()', AI.read_text())
        self.assertIn('return -1;', AI.read_text())
        for path in BREAKS:
            source = path.read_text()
            self.assertIn('getExecutionProgress()', source)
            self.assertIn('blockDamage * 100.0F', source)
            self.assertIn('Math.min(100,', source)
        self.assertIn('delay * 100 / (HARVEST_DELAY_TICKS + 1)', HARVEST.read_text())
        self.assertIn('target instanceof LivingEntity', ATTACK.read_text())
        self.assertIn('living.getHealth() / max', ATTACK.read_text())
        code = MENU.read_text()
        self.assertIn('activeAI.getExecutionProgress()', code)
        self.assertIn('percent, progress, processor.getRobotName(robotId)', code)
        self.assertIn('for (long robotId : processor.getLinkedRobotIds())', code)
        self.assertNotIn('getLoadedRobots()', code)

    def test_all_screens_show_profession_specific_models_and_real_progress(self):
        for path in ROBOT_SCREENS:
            with self.subTest(screen=path):
                code = (ROOT / path).read_text()
                self.assertIn('ItemRobot.createRobotStack(BCRoboticsBoards.getById(id)', code)
                self.assertIn('renderRobotModel(pose, selected.board(),', code)
                self.assertIn('renderRobotModel(pose, robot.board(),', code)
                self.assertNotIn('Temporary item preview', code)
                self.assertNotIn('panelText(pose, selected.progress()', code)
                self.assertNotIn('Progress: ', code)
                self.assertIn('RobotProcessorUiText.text("queue", selected.queued(), processorMenu.getMaxExecutionQueue())', code)
                self.assertIn('boardName(robot.board()) + " - " + RobotProcessorUiText.status(robot.status())', code)
                self.assertIn('draggingScrollbar', code)
                self.assertIn('scrollTo(mouseY);', code)
                self.assertIn('mouseDragged(double mouseX, double mouseY', code)
                self.assertIn('mouseReleased(double mouseX, double mouseY', code)
                self.assertIn('drawListBackground', code)
                self.assertIn('panelSmallText', code)
                self.assertIn('fitText(text', code)

    def test_older_renderers_spin_in_3d_using_actual_baked_model(self):
        for path in ROBOT_SCREENS[:3]:
            code = (ROOT / path).read_text()
            self.assertIn('renderStatic(stack,', code)
            self.assertTrue('TransformType.NONE' in code or 'ItemDisplayContext.NONE' in code)
            self.assertNotIn('rotationDegrees(18.0F)', code)
            self.assertNotIn('rotationDegrees(24.0F)', code)
            self.assertIn('modelPose.mulPose(', code)
            self.assertIn('(System.currentTimeMillis() % 12000L) * 0.03F', code)
        for path in ROBOT_SCREENS[3:]:
            code = (ROOT / path).read_text()
            self.assertTrue('graphics.item(stack, -8, -8);' in code or 'graphics.renderItem(stack, -8, -8);' in code)
            self.assertIn('BCGraphics.scale(graphics, scale, scale);', code)

    def test_library_positions_fit_existing_background(self):
        for path in ROBOT_SCREENS:
            code = (ROOT / path).read_text()
            self.assertIn('x >= leftPos + 93 && x < leftPos + 101', code)
            self.assertIn('int y = 23 + i * ROW_HEIGHT', code)
            self.assertIn('panelFill(pose, 8, top, 93, bottom, baseColor);', code)
            self.assertIn('panelFill(pose, 9, top + 1, 92, bottom - 1, 0x77_50_69_83);', code)
            self.assertIn('VISIBLE_ROWS = 7', code)
            self.assertIn('ROW_HEIGHT = 15', code)
            self.assertIn('45, 176, 162', code)
            self.assertIn('PLUS_X = 238, MINUS_X = 218, ADJUST_Y = 74', code)
            self.assertIn('HOME_X = 214, HOME_Y = 136', code)
            self.assertIn('UNLINK_X = 214, UNLINK_Y = 154', code)
            self.assertNotIn('Progress: ', code)


if __name__ == '__main__':
    unittest.main()
