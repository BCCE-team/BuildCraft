"""Regression guards for the processor's new name editor and execution queue setting.

Source-level checks: these do not claim to compile Minecraft or test gameplay.
"""
from pathlib import Path
import json
import unittest

from PIL import Image
from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[2]
TILE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java'
MENU = ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'
ATLAS = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png'

class RobotProcessorQueueAndNames(unittest.TestCase):
    def test_queue_has_server_validated_bounds_and_persistence_in_both_families(self):
        s = TILE.read_text()
        menu = MENU.read_text()
        for part in ('MIN_EXECUTION_QUEUE = 1', 'MAX_EXECUTION_QUEUE = 8',
                     'DEFAULT_EXECUTION_QUEUE = 5', 'setMaxExecutionQueue(int value)',
                     'Math.max(MIN_EXECUTION_QUEUE', 'setChanged();',
                     'data.writeInt("maxExecutionQueue", maxExecutionQueue)',
                     'tag.putInt("maxExecutionQueue", maxExecutionQueue)',
                     'getMaxExecutionQueue()'):
            self.assertIn(part, s)
        for part in ('ID_QUEUE_STATE', 'ID_SET_QUEUE', 'sendMessage(ID_SET_QUEUE',
                     'NetworkSecurity.requireRange(buffer.readUnsignedByte()',
                     'stillValid(playerInventory.player)',
                     'processor.setMaxExecutionQueue(limit)'):
            self.assertIn(part, menu)
        self.assertIn('movement and searching are not jobs', s)
        self.assertNotIn('getLoadedRobots()', menu)

    def test_aliases_only_apply_to_explicit_links_and_survive_world_save(self):
        s = TILE.read_text()
        menu = MENU.read_text()
        for part in ('linkedRobotIds.contains(robotId)', 'linkedStations.contains(key)',
                     'serializeRobotNames()', 'serializeStationNames()',
                     'restoreNames(', 'data.writeString("robotNames"',
                     'data.writeString("stationNames"', 'tag.putString("robotNames"',
                     'tag.putString("stationNames"', 'robotNames.remove(robotId)',
                     'stationNames.remove(new StationKey(pos, side))'):
            self.assertIn(part, s)
        self.assertIn('ID_RENAME_ROBOT', menu)
        self.assertIn('ID_RENAME_STATION', menu)
        self.assertIn('processor.renameRobot(robotId, name)', menu)
        self.assertIn('processor.renameStation(pos, Direction.from3DDataValue(direction), name)', menu)

    def test_latest_atlas_buttons_have_pixel_content_and_old_map_location_slot_is_gone(self):
        image = Image.open(ATLAS).convert('RGBA')
        self.assertEqual(image.size, (512, 512))
        for x,y in [(0,448), (16,448), (32,448), (48,448), (64,448),
                    (0,464), (16,464), (32,464), (48,464), (64,464),
                    (32,432), (32,480), (48,480), (32,496), (48,496)]:
            with self.subTest(sprite=(x,y)):
                pixels = [image.getpixel((x+dx,y+dy))[3] for dx in range(16) for dy in range(16)]
                self.assertGreater(sum(px>0 for px in pixels), 150)
        for x in range(213, 241):
            for y in range(25,43):
                self.assertEqual(image.getpixel((x,y))[3], 255)
        menu = MENU.read_text()
        self.assertIn('mayPlace(ItemStack stack) { return false; }',menu)
        self.assertIn('mayPickup(Player player) { return false; }',menu)
        self.assertNotIn('relocateSlot(2, 225, 25)',menu)

    def test_all_screen_versions_use_new_icons_and_shift_controls(self):
        for name in ROBOT_SCREENS:
            with self.subTest(name=name):
                s=(ROOT/name).read_text()
                for part in ('MODE_X = 8, RENAME_X = 26, MODE_Y = 4',
                             'GuiIcon(BACKGROUND, 64, 448, 16, 16, 512)',
                             'GuiIcon(BACKGROUND, 32, 432, 16, 16, 512)',
                             'GuiIcon(BACKGROUND, 32, 480, 16, 16, 512)',
                             'GuiIcon(BACKGROUND, 48, 480, 16, 16, 512)',
                             'getMaxExecutionQueue()',
                             'setMaxExecutionQueue(',
                             'processorMenu.renameRobot(', 'processorMenu.renameStation(',
                             'renameField.setMaxLength(32)', 'title.getString()',
                             'if (renaming) return renameField.charTyped('):
                    self.assertIn(part,s)

    def test_title_translation_uses_external_localizations_without_resource_collision(self):
        root = ROOT/'source-shared/src/main/resources/assets/buildcraft/lang'
        english = json.loads((root/'en_us.json').read_text(encoding='utf-8'))
        self.assertEqual(english['block.buildcraftrobotics.robot_processor'], 'Robot Control Panel')
        self.assertFalse((root/'ru_ru.json').exists(),
                         'Russian translation is staged from BCCE-team/Localizations')

if __name__ == '__main__':
    unittest.main()
