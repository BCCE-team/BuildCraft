"""Control-panel map navigation and translatable GUI help across supported targets."""
from pathlib import Path
import json
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
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

if __name__=='__main__': unittest.main()
