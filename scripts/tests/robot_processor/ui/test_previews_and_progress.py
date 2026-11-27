"""Robot Processor preview, measurable work progress, and linked-entry list checks.

Source-level regression tests, not a substitute for compiling with Minecraft libraries.
"""
import unittest
from pathlib import Path

from scripts.tests.robot_processor.ui.test_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[4]
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
        # Harvest is delayed but currently has no public progress override;
        # do not require a formula that no longer exists in production AI.
        self.assertIn('HARVEST_DELAY_TICKS = 12;', HARVEST.read_text())
        self.assertIn('if (delay++ <= HARVEST_DELAY_TICKS)', HARVEST.read_text())
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
                self.assertIn('ItemRobot.createRobotStack(BCRoboticsBoards.getById(boardId)', code)
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
            self.assertIn('RobotProcessorPreview.render(getActiveGraphics(), stack,', code)
            self.assertNotIn('graphics.item(stack, -8, -8);', code)
            self.assertNotIn('graphics.renderItem(stack, -8, -8);', code)

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


# Consolidated related checks from test_task_labels_and_lighting.py.
from pathlib import Path

import shutil

import subprocess

import tempfile

import unittest

from scripts.tests.robot_processor.ui.test_panel_layout import ROBOT_SCREENS

ROBOT_TASK_NAMES = ROOT / 'source-shared/src/main/java/buildcraft/robotics/RobotTaskNames.java'

class RobotPreviewAndTaskNameTests(unittest.TestCase):
    def test_list_icon_has_a_larger_centered_viewport_in_every_version(self):
        for source in ROBOT_SCREENS:
            with self.subTest(path=source):
                code = (ROOT / source).read_text(encoding='utf-8')
                self.assertIn('renderRobotModel(pose, robot.board(), robot.charge(), 18, y + 7, 24, false);', code)
                self.assertIn('29, y - 1, 57', code)
                self.assertIn('29, y + 5, 57', code)
                self.assertIn('renderRobotModel(pose, selected.board(), selected.charge(), 25, 160, 32, true);', code)

    def test_old_renderers_use_front_lighting_and_restore_default_lighting(self):
        for source in ROBOT_SCREENS[:3]:
            with self.subTest(path=source):
                code = (ROOT / source).read_text(encoding='utf-8')
                self.assertIn('import com.mojang.blaze3d.platform.Lighting;', code)
                self.assertIn('Lighting.setupForFlatItems();', code)
                self.assertIn('Lighting.setupFor3DItems();', code)
                self.assertIn('LightTexture.FULL_BRIGHT', code)
                self.assertIn('finally {\n            Lighting.setupFor3DItems();', code)

    def test_short_task_names_are_used_on_both_sides_of_network(self):
        menu = MENU.read_text(encoding='utf-8')
        self.assertIn('String task = RobotTaskNames.shortName(rawTask);', menu)
        for source in ROBOT_SCREENS:
            with self.subTest(path=source):
                code = (ROOT / source).read_text(encoding='utf-8')
                self.assertIn('RobotProcessorUiText.text("task", RobotProcessorUiText.task(selected.task()))', code)
                self.assertNotIn('compactTaskLabel(', code)

    def test_task_classification_is_meaningful_and_never_truncated(self):
        javac, java = shutil.which('javac'), shutil.which('java')
        if not javac or not java:
            self.skipTest('Java compiler or runtime not available')
        source = r'''import buildcraft.robotics.RobotTaskNames;
public class TaskNameCheck {
  public static void main(String[] args) {
    String[][] cases = {
      {"BoardRobotMiner", "Idle"},
      {"Board Robot Miner", "Idle"},
      {"BoardRobotLumberjack", "Idle"},
      {"AIRobotBreak", "Mine"},
      {"SearchAndGotoBlock", "Search"},
      {"AIRobotHarvest", "Harvest"},
      {"AIRobotPlant", "Plant"},
      {"AIRobotRecharge", "Charge"},
      {"AIRobotGoAndLinkToDock", "Dock"},
      {"AIRobotGotoStationToUnload", "Unload"},
      {"AIRobotLoadFluids", "Load"},
      {"AIRobotAttack", "Attack"},
      {"AIRobotFetchAndEquipItemStack", "Fetch"},
      {"AIRobotStripesHandler", "Use Tool"},
      {"ThirdPartyVeryLongCustomAction", "Work"},
      {"Offline", "Offline"},
      {"", "Idle"}
    };
    for (String[] test : cases) {
      String result = RobotTaskNames.shortName(test[0]);
      if (!result.equals(test[1]) || result.length() > 9)
        throw new AssertionError(test[0] + " expected " + test[1] + " got " + result);
    }
  }
}'''
        with tempfile.TemporaryDirectory(prefix='bc-robot-task-names-') as work:
            file = Path(work, 'TaskNameCheck.java')
            file.write_text(source, encoding='utf-8')
            subprocess.run([javac, '--release', '17', '-d', work, str(ROBOT_TASK_NAMES), str(file)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            finished = subprocess.run([java, '-cp', work, 'TaskNameCheck'],
                           check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertEqual(0, finished.returncode, finished.stdout + finished.stderr)


if __name__ == "__main__":
    unittest.main()
