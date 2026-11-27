"""Guard the new processor image layout and authenticated Home/Unlink actions.

This is a source/asset integration test. A Minecraft client/server run is still required.
"""
from pathlib import Path
import unittest
from PIL import Image

from scripts.tests.robot_processor.ui.test_panel_layout import ROBOT_SCREENS, ZONE_SOURCES

ROOT = Path(__file__).resolve().parents[4]
TILE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java'
MENU = ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'
ATLAS = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png'


class RobotProcessorHomeAndUnlinkTests(unittest.TestCase):
    def test_new_atlas_geometry_and_sprite_coordinates(self):
        image = Image.open(ATLAS).convert('RGBA')
        self.assertEqual(image.size, (512, 512))
        self.assertEqual(image.getpixel((200, 271)), (0, 0, 0, 255))
        self.assertEqual(image.getpixel((200, 272))[3], 0)
        for x, y in [(0, 448), (16, 448), (32, 448), (32, 432), (48, 448), (64, 448), (64, 464)]:
            with self.subTest(sprite=(x,y)):
                self.assertNotEqual(image.getpixel((x + 8, y + 8))[3], 0)
        for name in ZONE_SOURCES:
            with self.subTest(file=name):
                text = (ROOT / name).read_text()
                self.assertIn('new GuiIcon(TEXTURE_PROCESSOR, 0, 0, PROCESSOR_WIDTH, 272, 512)', text)
                self.assertIn('processorMode ? 104 : MAP_X', text)
                self.assertIn('processorMode ? 246 : COLOUR_X', text)
                self.assertIn('processorMode ? 195 : COLOUR_Y', text)
                self.assertIn('processorMode ? 158 : HISTORY_Y', text)
                self.assertIn('processorMode ? PROCESSOR_WIDTH : SIZE_X', text)

    def test_home_uses_force_visual_only_with_shift_and_unlink_is_separate(self):
        for name in ROBOT_SCREENS:
            with self.subTest(file=name):
                s = (ROOT / name).read_text()
                self.assertIn('HOME_X = 214, HOME_Y = 136', s)
                self.assertIn('UNLINK_X = 214, UNLINK_Y = 154', s)
                self.assertIn('HOME_ICON_FORCE', s)
                self.assertTrue(
                    'hasShiftDown() ? HOME_ICON_FORCE : HOME_ICON_HOVER' in s
                    or 'modifierShiftDown() ? HOME_ICON_FORCE : HOME_ICON_HOVER' in s,
                    'Home force icon should reflect the active Shift state',
                )
                self.assertIn('processorMenu.forceRobotChunk(robot.id())', s)
                self.assertIn('processorMenu.recall(robot.id())', s)
                self.assertIn('processorMenu.unlinkRobot(robot.id())', s)
                self.assertIn('processorMenu.unlinkStation(', s)
                self.assertNotIn('panelText(pose, "[Station]"', s)

    def test_server_validates_links_and_lifetime_before_actions(self):
        menu = MENU.read_text()
        self.assertIn('ID_FORCE_CHUNK', menu)
        self.assertIn('ID_UNLINK_ROBOT', menu)
        self.assertIn('ID_UNLINK_STATION', menu)
        self.assertIn('stillValid(playerInventory.player)', menu)
        self.assertIn('processor.isRobotLinked(robotId)', menu)
        self.assertIn('processor.unlinkStation(pos, Direction.from3DDataValue(direction))', menu)
        self.assertIn('NetworkSecurity.requireRange(buffer.readUnsignedByte(), 0, 5', menu)
        self.assertIn('replaceSlot(HDD_SLOT, new SlotBase(disk, 0, HDD_X, HDD_Y))', menu)
        self.assertIn('getLastKnownRobotPosition(robotId)', menu)
        self.assertNotIn('getLoadedRobots()', menu)

    def test_force_tickets_are_bounded_persisted_and_released(self):
        tile = TILE.read_text()
        for text in ('implements IChunkLoadingTile', 'MAX_FORCED_ROBOT_CHUNKS = 16',
                     'ChunkLoaderManager.loadChunksForTile(this)',
                     'ChunkLoaderManager.releaseChunksFor(this)',
                     'forceLoadedRobotIds.remove(robotId)', 'linkedRobotIds.contains(robotId)',
                     'knownRobotPositions', 'forcedRobotChunks', 'getChunksToLoad()',
                     'getLoadType()', 'chunkLoadingStartupDelay = 40'):
            with self.subTest(check=text):
                self.assertIn(text, tile)
        self.assertNotIn('setChunkForced(', tile)


if __name__ == '__main__':
    unittest.main()
