"""Control-panel map navigation and translatable GUI help across supported targets."""
from pathlib import Path
import json
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0,str(ROOT/'scripts'))
from source_config import load_properties,target_layout
from source_layout import resolve_effective_source, _materialize_text_file

PACK = 'src/main/java/buildcraft/robotics/'
TARGETS = ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
           '26.1.2-neoforge','26.2-neoforge','26.3-neoforge','26.3-fabric')

def source_for(target,java):
    import tempfile
    props=load_properties(); layout=target_layout(target, props)
    relative=PACK+java
    owner=resolve_effective_source(layout,props,relative)
    assert owner, f'No {java} for {target}'
    with tempfile.TemporaryDirectory() as tmp:
        out=Path(tmp)/relative
        _materialize_text_file(owner,out,logical_relative=relative,
            minecraft=props[f'target.{target}.deps.minecraft'],family=layout.family,
            platform=layout.platform,preprocess=True,
            native_source=bool(layout.family_downport_root and owner.is_relative_to(layout.family_downport_root)),
            native_passthrough=False)
        return out.read_text(encoding='utf-8')

class RobotProcessorMapAndLocalizedTooltipTests(unittest.TestCase):
    def test_26x_support_right_click_recenter_and_right_drag_within_work_area(self):
        for target in TARGETS[4:]:
            with self.subTest(target=target):
                java=source_for(target,'gui/GuiZonePlanner.java')
                self.assertIn('if (mouseButton == 1) {',java)
                self.assertIn('panningMap = true;',java)
                self.assertIn('if (panningMap && mouseButton == 1)',java)
                self.assertIn('updateMapCenter(panCenterX - deltaX * blocksPerPixel',java)
                self.assertIn('ZonePlannerEditor.Bounds bounds = processorWorkBounds();',java)
                self.assertIn('Math.max(bounds.minX()',java)
                self.assertIn('Math.max(bounds.minZ()',java)
                self.assertIn('if (button == 1 && panningMap)',java)
                self.assertIn('if (!inProcessorWorkArea(clicked.getX() >> 4',java)

    def test_all_processor_views_use_shared_translations_and_help(self):
        for target in TARGETS:
            with self.subTest(target=target):
                java=source_for(target,'gui/GuiRobotProcessor.java')
                self.assertIn('if (detail.isEmpty()) detail = contextualHelp(mouseX, mouseY);',java)
                self.assertIn('RobotProcessorUiText.help("queue_plus")',java)
                self.assertIn('RobotProcessorUiText.help("queue_minus")',java)
                self.assertIn('RobotProcessorUiText.help("cargo")',java)
                self.assertIn('RobotProcessorUiText.zoneHelp("undo")',java)
                self.assertIn('RobotProcessorUiText.text("queue",',java)
                self.assertIn('RobotProcessorUiText.status(',java)
                self.assertNotIn('panelText(pose, "No linked"',java)

    def test_every_shared_localization_key_is_present_in_english_language(self):
        helper=(ROOT/'source-shared/src/main/java/buildcraft/robotics/gui/RobotProcessorUiText.java').read_text(encoding='utf-8')
        lang=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text(encoding='utf-8'))
        prefix='gui.buildcraftrobotics.robot_processor.'
        self.assertEqual('Robot Control Panel',lang['block.buildcraftrobotics.robot_processor'])
        self.assertEqual('Robot Controller',lang['item.buildcraftrobotics.controller'])
        for key in ('help.home.title','help.home.desc','help.force.title','help.force.desc',
                    'help.queue.title','help.queue.desc','status.docked','task.mine','board.miner',
                    'scan.percent','diag.load.title','diag.nav.title'):
            self.assertIn(prefix+key,lang)
        for control in ('mode_stations','mode_robots','rename_robot','rename_station','queue_plus',
                        'queue_minus','home','force','unlink_robot','unlink_station','cargo',
                        'map','robot_list','station_list','list_scroll','cargo_scroll','queue_limit',
                        'station_info','status_colors'):
            for suffix in ('title','desc'): self.assertIn(prefix+'help.'+control+'.'+suffix,lang)
        for key in ('map.title','map.desc','layers.title','layers.desc'):
            self.assertIn('buildcraftrobotics.help.robot_processor.'+key,lang)
        for tool in ('eraser','rectangle','oval','pen','undo','redo','clear'):
            for suffix in ('title','desc'):
                self.assertIn('buildcraftrobotics.help.zone_planner.'+tool+'.'+suffix,lang)
        self.assertIn('Component.translatable(PREFIX + key, args)',helper)
        self.assertFalse((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/ru_ru.json').exists(),
                         'Russian locale must come from BuildCraft-Localizations, not duplicate the pack')


# Consolidated related checks from test_work_area.py.
from pathlib import Path

import shutil

import subprocess

import tempfile

import unittest

from scripts.tests.test_zone_planner_editing import EDITOR, STUBS, GUI_PATHS

from scripts.tests.robot_processor.ui.test_panel_layout import ROBOT_SCREENS

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
                self.assertIn('RobotProcessorUiText.text("no_linked")', s)
                self.assertNotIn('"No linked robots"', s)
                self.assertIn('202 - font.width(message)', s)
                self.assertIn('panelText(pose, message, x, 155, 202', s)

    def test_all_zone_variants_share_new_button_locations_and_work_area_limit(self):
        for path in GUI_PATHS:
            with self.subTest(path=path):
                s = (ROOT/path).read_text('utf-8')
                self.assertIn('new GuiIcon(TEXTURE_PROCESSOR, 0, 0, PROCESSOR_WIDTH, 272, 512)', s)
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


if __name__ == "__main__":
    unittest.main()
