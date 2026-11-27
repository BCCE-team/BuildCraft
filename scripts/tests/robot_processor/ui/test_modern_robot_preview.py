"""Check legacy preview geometry and the actual generated modern renderer sources."""
import json
import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

BASE = 'src/main/java/buildcraft/robotics/'
PREVIEW = BASE + 'client/render/preview/'
MODERN = ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
OLD = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge')
CLASSES = ('RobotProcessorPreview', 'RobotPreviewRenderState', 'RobotPreviewPipRenderer', 'RobotPreviewSubmission')


def generated(target, relative):
    props = load_properties()
    layout = target_layout(target, props)
    path = resolve_effective_source(layout, props, relative)
    if path is None:
        return None
    downports = tuple(root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root is not None)
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / 'source.java'
        _materialize_text_file(path, dest, logical_relative=relative,
                               minecraft=props[f'target.{target}.deps.minecraft'],
                               family=layout.family, platform=layout.platform, preprocess=True,
                               native_source=any(path.is_relative_to(root) for root in downports),
                               native_passthrough=(layout.platform == 'fabric' and path.is_relative_to(layout.family_platform_root)))
        return dest.read_text(encoding='utf-8')


class ModernRobotPreviewTests(unittest.TestCase):
    def test_every_modern_target_uses_native_preview_and_loader_registration(self):
        for target in MODERN:
            with self.subTest(target=target):
                preview, state, renderer, submit = (generated(target, PREVIEW + name + '.java') for name in CLASSES)
                self.assertIn('ItemDisplayContext.NONE', preview)
                self.assertIn('int side = pixelSize / 2;', preview)
                self.assertIn('8, 8, side, side, 8, 8, 32, 32', preview)
                self.assertIn('robotTexture()', preview)
                self.assertIn('textures/entities/overlay_side.png', preview)
                self.assertIn('BCGraphics.nextLayer(graphics)', preview)
                self.assertNotIn('_active.png', preview)
                self.assertIn('180.0F + (System.currentTimeMillis() % 12000L) * 0.03F', renderer)
                self.assertIn('ITEMS_FLAT', renderer)
                self.assertIn('FULL_BRIGHT', renderer)
                self.assertIn('state.model().submit(', renderer)
                self.assertIn('x0 + size', submit)
                if target == '26.3-fabric':
                    client = generated(target, BASE + 'client/FabricRoboticsClient.java')
                    self.assertIn('PictureInPictureRendererRegistry.register(context -> new RobotPreviewPipRenderer())', client)
                    self.assertIn('addPicturesInPictureState(state)', submit)
                    self.assertNotIn('net.neoforged', submit)
                else:
                    self.assertIn('event.register(RobotPreviewRenderState.class, RobotPreviewPipRenderer::new)', submit)
                    self.assertIn('submitPictureInPictureRenderState(state)', submit)
                if target.startswith(('26.2-', '26.3-')):
                    self.assertIn('Matrix3x2fc pose', state)
                    self.assertIn('SubmitNodeCollector collector', renderer)
                    self.assertIn('public RobotPreviewPipRenderer() { super(); }', renderer)
                    self.assertIn('gameRenderer.lighting()', renderer)
                    self.assertNotIn('bufferSource.endBatch()', renderer)
                else:
                    self.assertNotIn('Matrix3x2fc pose', state)
                    self.assertIn('MultiBufferSource.BufferSource buffers', renderer)
                    self.assertIn('dispatcher.renderAllFeatures()', renderer)
                    self.assertIn('gameRenderer.getLighting()', renderer)
                gui = generated(target, BASE + 'gui/GuiRobotProcessor.java')
                self.assertIn('RobotProcessorPreview.render(getActiveGraphics(), stack, leftPos + cx, topPos + cy, pixelSize, rotate)', gui)
                self.assertIn('renderRobotModel(pose, robot.board(), robot.charge(), 18, y + 7, 24, false)', gui)
                self.assertIn('renderRobotModel(pose, selected.board(), selected.charge(), 25, 160, 32, true)', gui)

    def test_legacy_targets_do_not_include_new_preview_backend(self):
        for target in OLD:
            with self.subTest(target=target):
                for name in CLASSES:
                    self.assertIsNone(generated(target, PREVIEW + name + '.java'))
                gui = generated(target, BASE + 'gui/GuiRobotProcessor.java')
                self.assertIn('renderStatic(stack,', gui)
                self.assertNotIn('RobotProcessorPreview', gui)

    def test_side_uv_and_dimensions_match_all_original_robot_textures(self):
        assets = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics'
        model = json.loads((assets / 'models/robot.json').read_text())
        self.assertEqual(model['texture_size'], [32, 32])
        body = model['elements'][0]
        self.assertEqual([b - a for a, b in zip(body['from'], body['to'])], [8, 8, 8])
        self.assertEqual(body['faces']['north']['uv'], [4, 4, 8, 8])
        boards = (ROOT / 'source-families/1.21.X' / BASE / 'BCRoboticsBoards.java').read_text()
        names = set(re.findall(r'"(robot_[a-z_]+)"', boards))
        self.assertEqual(len(names), 21)
        for name in names:
            with self.subTest(robot=name):
                data = (assets / 'textures/entities' / (name + '.png')).read_bytes()
                self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                self.assertEqual(struct.unpack('>II', data[16:24]), (32, 32))


if __name__ == '__main__':
    unittest.main()
