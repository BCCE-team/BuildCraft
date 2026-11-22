"""Regressions for short AI action labels and front-lit robot previews."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[2]
ROBOT_TASK_NAMES = ROOT / 'source-shared/src/main/java/buildcraft/robotics/RobotTaskNames.java'
MENU = ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'


class RobotPreviewAndTaskNameTests(unittest.TestCase):
    def test_list_icon_has_a_larger_centered_viewport_in_every_version(self):
        for source in ROBOT_SCREENS:
            with self.subTest(path=source):
                code = (ROOT / source).read_text(encoding='utf-8')
                self.assertIn('renderRobotModel(pose, robot.board(), 18, y + 7, 24, false);', code)
                self.assertIn('29, y - 1, 57', code)
                self.assertIn('29, y + 5, 57', code)
                self.assertIn('renderRobotModel(pose, selected.board(), 25, 160, 32, true);', code)

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


if __name__ == '__main__':
    unittest.main()
