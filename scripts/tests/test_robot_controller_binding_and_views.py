"""Integration guards for the user-supplied Robot Processor atlas and controller.

These tests verify source/resource wiring and all supported source family forms.
They do not substitute for an in-game interaction test or Gradle compilation.
"""
from pathlib import Path
import json
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS, ZONE_SOURCES


class RobotControllerIntegrationTests(unittest.TestCase):
    def test_icon_and_layout_coordinates_match_supplied_atlas(self):
        from PIL import Image
        image = Image.open(ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png')
        self.assertEqual(image.size, (512, 512))
        self.assertEqual(image.getpixel((128, 271))[3], 255)
        self.assertEqual(image.getpixel((128, 272))[3], 0)
        # The mode icons are embedded in the lower-left sprite area; no additional texture.
        self.assertNotEqual(image.getpixel((7, 456)), (0, 0, 0, 0))
        self.assertNotEqual(image.getpixel((23, 456)), (0, 0, 0, 0))
        for name in ROBOT_SCREENS:
            text = (ROOT / name).read_text()
            self.assertIn('GuiIcon(BACKGROUND, 32, 448, 16, 16, 512)', text)
            self.assertIn('GuiIcon(BACKGROUND, 48, 448, 16, 16, 512)', text)
            self.assertIn('return inButton(x, y, MODE_X, MODE_Y);', text)
            self.assertIn('MODE_X = 8, RENAME_X = 26, MODE_Y = 4', text)
            self.assertIn('stationMode = !stationMode;', text)
            self.assertIn('renderStations(pose)', text)
        for name in ZONE_SOURCES:
            text = (ROOT / name).read_text()
            self.assertIn('processorMode ? 272 : SIZE_Y', text)
            self.assertIn('processorMode ? 22 : MAP_Y', text)
            self.assertIn('processorMode ? 195 : COLOUR_Y', text)
            self.assertIn('processorMode ? 177 : SHAPE_Y', text)

    def test_inventory_and_map_slot_use_new_artwork_coordinates(self):
        menu = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        self.assertIn('replaceSlot(2, new SlotBase(', menu)
        self.assertIn('191 + row * 18', menu)
        self.assertIn('8 + col * 18, 249', menu)
        self.assertNotIn('new SlotBase(storage,', menu)

    def test_controller_uses_existing_local_texture_without_copying(self):
        model = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/models/item/controller.json'
        data = json.loads(model.read_text())
        self.assertEqual(data['textures']['layer0'], 'buildcraftrobotics:items/controller')
        texture = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/items/controller.png'
        self.assertTrue(texture.is_file(), 'The controller model must resolve to the shared 32x32 icon')
        self.assertFalse((ROOT / 'source-families/1.21.X/src/main/resources/assets/buildcraftrobotics/textures/items/controller.png').exists())
        # 1.21.1 still uses legacy models/item JSON; the modern items/ definition
        # must not be copied from the shared 1.21.X family into that target.
        premature = ROOT / 'source-families/1.21.X/src/main/resources/assets/buildcraftrobotics/items/controller.json'
        self.assertFalse(premature.exists())
        # The 26.X family may carry the explicit one-to-one wrapper. For
        # 1.21.11 it is generated from models/item/controller.json instead.
        modern = ROOT / 'source-families/26.X/src/main/resources/assets/buildcraftrobotics/items/controller.json'
        self.assertEqual(json.loads(modern.read_text())['model']['model'], 'buildcraftrobotics:item/controller')

    def test_explicit_links_are_persisted_and_not_discovered_automatically(self):
        tile = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/tile/TileRobotProcessor.java').read_text()
        item = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/item/ItemRobotController.java').read_text()
        menu = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java').read_text()
        self.assertIn('public boolean linkRobot(long robotId)', tile)
        self.assertIn('public boolean linkStation(BlockPos pos, Direction side)', tile)
        self.assertIn('writeLongArray("linkedRobots"', tile)
        self.assertIn('writeLongArray("linkedStations"', tile)
        self.assertIn('writeIntArray("linkedStationSides"', tile)
        self.assertIn('savedRobotIds()', tile)
        self.assertIn('savedStationSides()', tile)
        self.assertIn('if (panel.linkRobot(robot.getRobotId(), robot.blockPosition()))', item)
        self.assertIn('if (panel.linkStation(pos, side))', item)
        self.assertIn('registry.getStation(pos, side)', item)
        self.assertIn('if (!level.hasChunkAt(pos)) return null;', item)
        self.assertIn('refreshStationSnapshots();', menu)
        self.assertNotIn('getLoadedRobots()', menu)

    def test_preprocessor_builds_old_and_modern_item_signatures(self):
        path = ROOT / 'source-shared/src/main/java/buildcraft/robotics/item/ItemRobotController.java'
        for mc, family, platform, return_type in (
                ('1.19.2', 'old', 'forge', 'InteractionResultHolder<ItemStack>'),
                ('1.20.1', 'old', 'forge', 'InteractionResultHolder<ItemStack>'),
                ('1.21.1', '1.21.X', 'neoforge', 'InteractionResultHolder<ItemStack>'),
                ('1.21.11', '1.21.X', 'neoforge', 'InteractionResult'),
                ('26.3', '26.X', 'neoforge', 'InteractionResult'),
                ('26.3', '26.X', 'fabric', 'InteractionResult')):
            with self.subTest(mc=mc, platform=platform):
                result = preprocess_text(path.read_text(), minecraft=mc, family=family, platform=platform)
                self.assertIn(f'public {return_type} use(Level world, Player player, InteractionHand hand)', result)
                self.assertNotIn('//?', result)


if __name__ == '__main__':
    unittest.main()
