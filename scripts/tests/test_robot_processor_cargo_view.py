"""Regression coverage for read-only robot cargo previews and two scrollbar skins.

These checks verify the source wiring, transport safety and materializable
conditional branches. They do not substitute for compiling inside Minecraft.
"""
from pathlib import Path
import struct
import unittest

from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_panel_layout import ROBOT_SCREENS

ROOT = Path(__file__).resolve().parents[2]
MENU = ROOT / 'source-shared/src/main/java/buildcraft/robotics/container/ContainerRobotProcessor.java'
TEXTURE = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png'
FLOWING_RENDERERS = (
    'version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
    'source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
    'source-platforms/neoforge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
    'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
    'source-families/26.X/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
    'source-family-platforms/26.X/fabric/src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java',
)


class RobotCargoPreviewTests(unittest.TestCase):
    def test_texture_replacement_keeps_main_atlas_and_second_scroll_skin(self):
        data = TEXTURE.read_bytes()
        self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
        self.assertEqual(struct.unpack('>II', data[16:24]), (512, 512))
        for screen in ROBOT_SCREENS:
            code = (ROOT / screen).read_text(encoding='utf-8')
            self.assertIn('507, 0, 5, 12, 512', code)
            self.assertIn('507, 12, 5, 12, 512', code)
            self.assertIn('(count > VISIBLE_ROWS ? SCROLL_THUMB : SCROLL_DISABLED)', code)
            self.assertIn('(count > CARGO_ROWS ? SCROLL_THUMB : SCROLL_DISABLED)', code)

    def test_cargo_is_a_read_only_4_row_list_with_independent_scrolling(self):
        for screen in ROBOT_SCREENS:
            with self.subTest(screen=screen):
                code = (ROOT / screen).read_text(encoding='utf-8')
                self.assertIn('CARGO_X = 175, CARGO_Y = 191', code)
                self.assertIn('CARGO_ROWS = 4, CARGO_ROW_HEIGHT = 18', code)
                self.assertIn('private boolean draggingCargoScrollbar;', code)
                self.assertIn('if (button == 0 && inCargoWindow(x, y)) return true;', code)
                self.assertIn('renderCargoTooltip(', code) if '1.19.2' not in screen else self.assertIn('renderComponentTooltip(', code)
                self.assertIn('cargoScrollBy(', code)
                self.assertIn('renderCargoItem(', code)
                self.assertIn('renderCargoFluid(', code)
                self.assertNotIn('addSlot(new Slot(', code)

    def test_server_transfers_only_linked_selected_robot_items_and_fluid(self):
        text = MENU.read_text(encoding='utf-8')
        self.assertIn('&& (requested == -1L || requested > 0 && processor.isRobotLinked(requested))', text)
        self.assertIn('if (robot == null || robot.isRemoved())', text)
        self.assertIn('robot.getContainerSize()', text)
        self.assertIn('robot.getItem(slot)', text)
        self.assertIn('robot.getFluidInTank(tank)', text)
        self.assertIn('ID_REQUEST_CARGO', text)
        self.assertIn('ID_CARGO', text)
        self.assertIn('NetworkSecurity.requireRange(buffer.readVarInt(), 0, MAX_CARGO_ENTRIES', text)
        self.assertIn('MAX_CARGO_ENTRIES = 32', text)
        self.assertIn('if (receivedRobotId == cargoRobotId)', text)
        self.assertIn('requestCargo(long robotId)', text)
        self.assertNotIn('robot.getInventory().clear()', text)
        self.assertNotIn('forceRobotChunk(robotId)', text[text.index('private void refreshCargoSnapshot()'):text.index('private void sendCargo(')])

    def test_flowing_sprite_selected_in_every_target_renderer(self):
        for path in FLOWING_RENDERERS:
            with self.subTest(renderer=path):
                code = (ROOT / path).read_text(encoding='utf-8')
                self.assertIn('public static void drawFlowingFluidForGui(', code)
                self.assertIn('FluidSpriteType.FLOWING', code)

    def test_item_codec_uses_vanilla_on_older_versions_and_registry_codec_on_modern(self):
        text = MENU.read_text(encoding='utf-8')
        for version, family, platform in (
            ('1.19.2', 'old', 'forge'), ('1.20.1', 'old', 'forge'),
            ('1.21.1', '1.21.X', 'neoforge'), ('1.21.11', '1.21.X', 'neoforge'),
            ('26.1.2', '26.X', 'neoforge'), ('26.3', '26.X', 'neoforge'),
            ('26.3', '26.X', 'fabric'),
        ):
            with self.subTest(version=version, platform=platform):
                source = preprocess_text(text, minecraft=version, family=family, platform=platform)
                if version in ('1.19.2', '1.20.1'):
                    self.assertIn('out.writeItem(stack);', source)
                    self.assertIn('return in.readItem();', source)
                    self.assertNotIn('ItemStackUtil.writeOptional', source)
                else:
                    self.assertIn('ItemStackUtil.writeOptional', source)
                    self.assertIn('ItemStackUtil.readOptional', source)
                    self.assertNotIn('out.writeItem(stack);', source)


if __name__ == '__main__':
    unittest.main()
