"""Regression coverage for the BCCE heat exchanger's model/texture wiring.

The multipart models are shared by every supported version; older Minecraft
model parsers must not encounter newer Blockbench Euler rotations.
"""
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'source-shared/src/main/resources/assets/buildcraftfactory'


def load_json(relative):
    return json.loads((ASSETS / relative).read_text(encoding='utf-8'))


class HeatExchangeAssetWiring(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.blockstate = load_json('blockstates/heat_exchange.json')['multipart']

    def matching_models(self, facing, part, connected_left, connected_right):
        values = {
            'facing': facing,
            'part': part,
            'connected_left': str(connected_left).lower(),
            'connected_right': str(connected_right).lower(),
        }
        return sorted(
            entry['apply']['model'].split(':', 1)[1]
            for entry in self.blockstate
            if all(values[key] in value.split('|') for key, value in entry['when'].items())
        )

    def test_all_sides_and_parts_render_without_missing_or_extra_models(self):
        for facing in ('north', 'east', 'south', 'west'):
            for part in ('start', 'middle', 'end'):
                for left in (False, True):
                    for right in (False, True):
                        with self.subTest(facing=facing, part=part, left=left, right=right):
                            models = self.matching_models(facing, part, left, right)
                            self.assertIn(f'block/heat_exchange_{part}', models)
                            self.assertIn(f'block/heat_exchange_{part}_glass', models)
                            self.assertEqual(4 if part == 'middle' else 2, len(models))
                            for model in models:
                                self.assertTrue((ASSETS / f'models/{model}.json').is_file())

    def test_physical_side_connections_match_clockwise_block_state_semantics(self):
        # BlockHeatExchange.PROP_CONNECTED_LEFT is clockwise from block facing.
        # Facing north => LEFT means EAST, the x+ end of the supplied meshes.
        for facing in ('north', 'east', 'south', 'west'):
            for left in (False, True):
                for right in (False, True):
                    with self.subTest(facing=facing, left=left, right=right):
                        models = self.matching_models(facing, 'middle', left, right)
                        self.assertIn('block/heat_exchange_flange_right' if left else 'block/heat_exchange_cap_right', models)
                        self.assertIn('block/heat_exchange_flange_left' if right else 'block/heat_exchange_cap_left', models)
        for left_or_right, x_expected in (('left', 2), ('right', 14)):
            for kind in ('flange', 'cap'):
                element = load_json(f'models/block/heat_exchange_{kind}_{left_or_right}.json')['elements'][0]
                self.assertTrue(element['to'][0] <= x_expected if left_or_right == 'left'
                                else element['from'][0] >= x_expected)

    def test_user_textures_resolve_and_glass_uses_original_texture(self):
        required = ('cap', 'cover', 'flange', 'glass', 'nozzle_cold',
                    'nozzle_hot', 'shell_side', 'shell_top')
        for name in required:
            self.assertTrue((ASSETS / f'textures/block/heat_exchange/{name}.png').is_file(), name)
        for name in ('start', 'middle', 'end'):
            body = load_json(f'models/block/heat_exchange_{name}.json')
            glass = load_json(f'models/block/heat_exchange_{name}_glass.json')
            self.assertEqual('minecraft:solid', body['render_type'])
            self.assertEqual('minecraft:translucent', glass['render_type'])
            self.assertEqual('buildcraftfactory:block/heat_exchange/glass', glass['textures']['glass'])
            self.assertEqual(4, len(glass['elements']))
            self.assertTrue(body['elements'])
            for model in (body, glass):
                self.assertNotIn('format_version', model)
                for texture_name, tex in model['textures'].items():
                    if texture_name == 'glass':
                        # Keep the translucent glass texture supplied with the exchanger model.
                        self.assertEqual('buildcraftfactory:block/heat_exchange/glass', tex)
                        continue
                    self.assertTrue(tex.startswith('buildcraftfactory:block/heat_exchange/'), tex)
                    target = ASSETS / ('textures/' + tex.split(':')[1] + '.png')
                    self.assertTrue(target.is_file(), target)
                for element in model['elements']:
                    self.assertNotIn('rotation', element,
                                     'All 90-degree Euler rotations must be baked into geometry')
                    for axis in range(3):
                        self.assertGreaterEqual(element['from'][axis], 0)
                        self.assertLessEqual(element['to'][axis], 16)
                        self.assertLessEqual(element['from'][axis], element['to'][axis])
                    for face in element['faces'].values():
                        self.assertIn(face['texture'][1:], model['textures'])

    def test_item_model_is_wired_for_old_and_26x_model_layouts(self):
        item = load_json('models/item/heat_exchange.json')
        self.assertGreaterEqual(len(item['elements']), 19)
        self.assertEqual('minecraft:translucent', item['render_type'])
        self.assertEqual('buildcraftfactory:block/heat_exchange/glass', item['textures']['glass'])
        # The inventory and JEI icon use the exact same glass panels as the world model.
        panes = [e for e in item['elements'] if e.get('name', '').startswith('glass_')]
        self.assertEqual(4, len(panes))
        world = load_json('models/block/heat_exchange_middle_glass.json')
        self.assertEqual(world['elements'],
                         [{k: v for k, v in pane.items() if k != 'name'} for pane in panes])
        for texture_name, tex in item['textures'].items():
            if texture_name == 'glass':
                continue  # Verified above as the original exchanger texture.
            self.assertTrue((ASSETS / ('textures/' + tex.split(':')[1] + '.png')).exists(), tex)
        modern = ROOT / 'source-families/26.X/src/main/resources/assets/buildcraftfactory/items/heat_exchange.json'
        self.assertEqual('buildcraftfactory:item/heat_exchange',
                         json.loads(modern.read_text(encoding='utf-8'))['model']['model'])

    def test_glass_uses_original_heat_exchanger_texture_with_translucent_render(self):
        texture = 'buildcraftfactory:block/heat_exchange/glass'
        png = ASSETS / 'textures/block/heat_exchange/glass.png'
        self.assertTrue(png.is_file())
        self.assertEqual(b'\x89PNG\r\n\x1a\n', png.read_bytes()[:8])
        for part in ('start', 'middle', 'end'):
            glass = load_json(f'models/block/heat_exchange_{part}_glass.json')
            self.assertEqual(texture, glass['textures']['glass'])
            self.assertEqual('minecraft:translucent', glass['render_type'])
        item = load_json('models/item/heat_exchange.json')
        self.assertEqual(texture, item['textures']['glass'])
        self.assertEqual('minecraft:translucent', item['render_type'])
        self.assertEqual(4, len([e for e in item['elements']
                                 if e.get('name', '').startswith('glass_')]))

    def test_resources_are_visible_on_all_supported_targets(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_config import load_properties, target_layout
        from source_layout import resolve_effective_source
        props = load_properties()
        for target in ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
                       '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge',
                       '26.3-neoforge'):
            with self.subTest(target=target):
                layout = target_layout(target, props)
                for file in ('blockstates/heat_exchange.json',
                             'models/block/heat_exchange_start.json',
                             'models/block/heat_exchange_start_glass.json',
                             'models/item/heat_exchange.json',
                             'textures/block/heat_exchange/glass.png'):
                    resolved = resolve_effective_source(
                        layout, props, 'src/main/resources/assets/buildcraftfactory/' + file)
                    self.assertEqual(ASSETS / file, resolved)


if __name__ == '__main__':
    unittest.main()
