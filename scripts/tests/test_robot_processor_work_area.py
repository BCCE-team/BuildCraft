"""Regression coverage for the Robot Processor's 7x7 map and GUI placement.

Java probe exercises production ZonePlannerEditor with test doubles; graphics
layout tests validate the existing supplied atlas, not a regenerated mock-up.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.test_zone_planner_editing import EDITOR, STUBS, GUI_PATHS
from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[2]
TILE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java'
ATLAS = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png'

PROBE = '''package buildcraft.robotics.zone;
import static buildcraft.robotics.zone.ZonePlannerEditor.*;
public class ProcessorBoundsProbe {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    Bounds work = new Bounds(-48, -48, 63, 63); // seven chunks per axis
    ZonePlan area = new ZonePlan();
    check(paint(area, Tool.RECTANGLE, -70, -70, 75, 75, true, p -> true, work),
          "rectangle clip should paint inside work area");
    check(area.get(-48,-48) && area.get(63,63), "rectangle must include the boundary");
    check(!area.get(-49,-48) && !area.get(64,63), "rectangle outside is never modified");
    check(paint(area, Tool.RECTANGLE, -70, -70, 75, 75, false, p -> true, work),
          "eraser should clear inside the same boundary");
    check(!area.get(-48,-48), "rectangle eraser worked");

    ZonePlan brush = new ZonePlan();
    check(paint(brush, Tool.BRUSH, 63, 63, 63, 63, true, p -> true, work),
          "brush should paint at the border");
    check(brush.get(63,63) && brush.get(62,62), "brush inside border");
    check(!brush.get(64,63) && !brush.get(63,64), "brush tip clipped at border");

    ZonePlan line = new ZonePlan();
    check(paint(line, Tool.LINE, -60, -60, 70, 70, true, p -> true, work),
          "line clip should change some cells");
    check(line.get(-48,-48) && line.get(63,63), "line meets each edge");
    check(!line.get(-49,-49) && !line.get(64,64), "line never paints outside");

    ZonePlan ellipse = new ZonePlan();
    check(paint(ellipse, Tool.ELLIPSE, -100, -100, 100, 100, true, p -> true, work),
          "ellipse should paint some cells");
    check(ellipse.get(0,0) && !ellipse.get(64,0), "ellipse clipped");
    System.out.println("RobotProcessor boundary probes passed");
  }
}
'''

class RobotProcessorWorkAreaTests(unittest.TestCase):
    def test_work_area_contains_exactly_seven_chunks_each_axis(self):
        s = TILE.read_text('utf-8')
        self.assertIn('WORK_AREA_RADIUS_CHUNKS = 3;', s)
        self.assertIn('Math.abs((long) chunkX - centerChunkX) <= WORK_AREA_RADIUS_CHUNKS', s)
        self.assertIn('Math.abs((long) chunkZ - centerChunkZ) <= WORK_AREA_RADIUS_CHUNKS', s)
        self.assertIn('super.setArea(index, limitToWorkArea(area, worldPosition));', s)
        self.assertIn('plan.copyChunksMatching(', s)
        for cx, cz in [(0,0),(-1,-1),(224,-301)]:
            seen = {(x,z) for x in range(cx-7,cx+8) for z in range(cz-7,cz+8)
                    if abs(x-cx)<=3 and abs(z-cz)<=3}
            self.assertEqual(len(seen), 49)
            self.assertIn((cx-3,cz+3), seen)
            self.assertNotIn((cx-4,cz), seen)

    def test_all_screen_variants_center_empty_labels(self):
        for path in ROBOT_SCREENS:
            with self.subTest(path=path):
                s = (ROOT/path).read_text('utf-8')
                self.assertIn('"No linked"', s)
                self.assertNotIn('"No linked robots"', s)
                self.assertIn('202 - font.width(message)', s)
                self.assertIn('panelText(pose, message, x, 155, 202', s)

    def test_all_zone_variants_share_new_button_locations_and_work_area_limit(self):
        for path in GUI_PATHS:
            with self.subTest(path=path):
                s = (ROOT/path).read_text('utf-8')
                self.assertIn('new GuiIcon(TEXTURE_PROCESSOR, 0, 0, 323, 272, 512)', s)
                self.assertIn('processorMode ? 246 : UNDO_X', s)
                self.assertIn('processorMode ? 264 : REDO_X', s)
                self.assertIn('processorMode ? 300 : CLEAR_X', s)
                self.assertIn('processorMode ? 158 : HISTORY_Y', s)
                self.assertIn('processorWorkBounds()', s)
                self.assertIn('if (!inProcessorWorkArea(chunkX, chunkZ)) continue;', s)
                self.assertIn('modified = restrictProcessorSelection(modified);', s)
                self.assertIn('argbToAbgr(0xFF_4C_4C_4C)', s)
                self.assertIn('processorMode ? 272 : SIZE_Y', s)

    def test_editor_bounds_restrict_paint_not_just_flood(self):
        self.assertIsNotNone(shutil.which('javac'), 'Java 17 compiler is required')
        with tempfile.TemporaryDirectory(prefix='bc-processor-workarea-') as temp:
            base = Path(temp)
            stubs = dict(STUBS)
            stubs['buildcraft/robotics/zone/ProcessorBoundsProbe.java'] = PROBE
            for name, text in stubs.items():
                target = base/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(text,'utf-8')
            subprocess.run(['javac','--release','17','-d',str(base/'classes'),
                            *[str(base/p) for p in stubs], str(EDITOR)],
                            check=True, capture_output=True, text=True)
            result = subprocess.run(['java','-cp',str(base/'classes'),
                                     'buildcraft.robotics.zone.ProcessorBoundsProbe'],
                                    check=True, capture_output=True,text=True)
            self.assertIn('boundary probes passed',result.stdout)

    def test_bottom_border_is_inside_newly_rendered_height(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest('Pillow unavailable')
        img = Image.open(ATLAS).convert('RGBA')
        self.assertEqual(img.size,(512,512))
        self.assertEqual(img.getpixel((128,271)),(0,0,0,255))
        self.assertEqual(img.getpixel((128,272))[3],0)

if __name__ == '__main__':
    unittest.main()
