"""Robot items and previews share entity base textures and reusable light overlays."""
from collections import Counter
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_ids, target_layout
from source_layout import resolve_effective_source
from transforms.resources import generate_legacy_resources_from_modern_canonical
from scripts.tests.robot_processor.ui.test_modern_robot_preview import generated, MODERN

ASSETS = 'src/main/resources/assets/buildcraftrobotics/'
PARENT = ASSETS + 'models/robot.json'
VARIANTS = ASSETS + 'models/item/robot/'
ATLAS = 'src/main/resources/assets/minecraft/atlases/blocks.json'
PREFIX = 'buildcraftrobotics:entities/'
OVERLAYS = {PREFIX + 'overlay_side', PREFIX + 'overlay_bottom'}


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


class RobotTextureLayersTests(unittest.TestCase):
    def test_base_body_and_light_shell_share_uvs(self):
        model = load_json(ROOT / 'source-shared' / PARENT)
        self.assertEqual('minecraft:cutout', model['render_type'])
        self.assertEqual(OVERLAYS, set(model['textures'].values()))
        body, lights = model['elements']
        self.assertEqual([4, 4, 4], body['from'])
        self.assertEqual([12, 12, 12], body['to'])
        self.assertEqual({'north', 'south', 'west', 'east', 'down'}, set(lights['faces']))
        self.assertTrue(all(0 < a-b <= 0.02 for a, b in zip(body['from'], lights['from'])))
        self.assertTrue(all(0 < a-b <= 0.02 for a, b in zip(lights['to'], body['to'])))
        for face, entry in lights['faces'].items():
            self.assertEqual(body['faces'][face]['uv'], entry['uv'])
            self.assertEqual('#all', body['faces'][face]['texture'])
            self.assertEqual('#overlay_bottom' if face == 'down' else '#overlay_side', entry['texture'])

    def test_all_item_professions_use_base_texture_on_all_targets(self):
        shared = ROOT / 'source-shared'
        variants = sorted(p.name for p in (shared / VARIANTS).glob('*.json'))
        self.assertEqual(21, len(variants))
        props = load_properties()
        for target in target_ids(props):
            layout = target_layout(target, props)
            with self.subTest(target=target):
                parent = resolve_effective_source(layout, props, PARENT)
                self.assertEqual(load_json(shared / PARENT), load_json(parent))
                for name in variants:
                    model = load_json(resolve_effective_source(layout, props, VARIANTS + name))
                    texture = model['textures']['all']
                    texture_name = {'empty.json':'robot_base', 'shepherd.json':'robot_sheepherd'}.get(name, 'robot_' + name[:-5])
                    self.assertEqual(PREFIX + texture_name, texture)
                    self.assertEqual('buildcraftrobotics:robot', model['parent'])
                    self.assertEqual('#all', model['textures']['particle'])
                    for sprite in {texture} | OVERLAYS:
                        file = resolve_effective_source(layout, props, ASSETS + 'textures/entities/' + sprite.split('/')[-1] + '.png')
                        self.assertIsNotNone(file, sprite)
                        png = file.read_bytes()
                        self.assertEqual(b'\x89PNG\r\n\x1a\n', png[:8])
                        self.assertEqual((32,32), struct.unpack('>II', png[16:24]))

    def test_effective_atlases_use_base_and_overlay_sprites(self):
        required = {load_json(p)['textures']['all'] for p in (ROOT / 'source-shared' / VARIANTS).glob('*.json')} | OVERLAYS
        self.assertEqual(23, len(required))
        props = load_properties()
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            generate_legacy_resources_from_modern_canonical(dest, minecraft='1.20.1', family='old', platform='forge')
            for target in target_ids(props):
                with self.subTest(target=target):
                    path = resolve_effective_source(target_layout(target, props), props, ATLAS)
                    if target == '1.19.2-forge':
                        self.assertIsNone(path)  # Legacy model bakery discovers model textures.
                        continue
                    if target == '1.20.1-forge':
                        path = dest / ATLAS
                    counts = Counter(s.get('resource') for s in load_json(path)['sources'])
                    for texture in required:
                        self.assertEqual(1, counts[texture], texture)
                    self.assertFalse(any(counts[texture + '_active'] for texture in required - OVERLAYS))

    def test_modern_portrait_layers_draw_in_order_and_preserve_native_preview(self):
        for target in MODERN:
            with self.subTest(target=target):
                preview = generated(target, 'src/main/java/buildcraft/robotics/client/render/preview/RobotProcessorPreview.java')
                self.assertNotIn('_active.png', preview)
                self.assertIn('"textures/entities/" + name + ".png"', preview)
                self.assertIn('"textures/entities/overlay_side.png"', preview)
                self.assertLess(preview.index('BCGraphics.blit(graphics, texture,'), preview.index('BCGraphics.nextLayer(graphics)'))
                self.assertLess(preview.index('BCGraphics.nextLayer(graphics)'), preview.index('graphics.blit(RenderPipelines.GUI_TEXTURED, SIDE_OVERLAY,'))
                self.assertIn('8, 8, side, side, 8, 8, 32, 32', preview)
                self.assertIn('8.0F, 8.0F, side, side, 8, 8, 32, 32, RobotBatteryVisuals.sideTint(stack)', preview)
                self.assertIn('ItemDisplayContext.NONE', preview)
                self.assertIn('RobotPreviewSubmission.submit(graphics, model, cx, cy, pixelSize)', preview)

    def test_world_renderer_still_uses_dynamic_overlay_state(self):
        props = load_properties()
        for target in target_ids(props):
            with self.subTest(target=target):
                path = resolve_effective_source(target_layout(target, props), props,
                    'src/main/java/buildcraft/robotics/client/render/RenderRobot.java')
                text = path.read_text(encoding='utf-8')
                self.assertIn('textures/entities/overlay_side.png', text)
                self.assertIn('textures/entities/overlay_bottom.png', text)
                self.assertIn('isAsleepForRendering()', text)
                self.assertIn('getEnergyForRendering()', text)
                self.assertNotIn('_active.png', text)


if __name__ == '__main__':
    unittest.main()
