"""Validate charge-driven resource dispatch, client registration, and the actual Java color math."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.robot_processor.ui.test_modern_robot_preview import generated, MODERN, OLD, BASE
from scripts.source_config import load_properties, target_layout
from scripts.source_layout import resolve_effective_source, _materialize_text_file
from scripts.transforms.resources import generate_target_resources

ROOT = Path(__file__).resolve().parents[4]
ASSETS = 'src/main/resources/assets/buildcraftrobotics/'
SHARED = ROOT / 'source-shared'
MODEL = ASSETS + 'models/'
POWER = 'buildcraftrobotics:robot_power'
BOARD = 'buildcraftrobotics:robot'
CHARGE = 'buildcraftrobotics:robot_charge'


def load(p):
    return json.loads(p.read_text(encoding='utf-8'))


def legacy_model(definition, properties):
    # Vanilla's overrides are traversed from last to first.
    for entry in reversed(definition['overrides']):
        if all(properties[key] >= minimum for key, minimum in entry['predicate'].items()):
            return entry['model']
    return definition['parent']


def modern_model(node, properties):
    while node['type'] == 'minecraft:range_dispatch':
        value = properties[node['property']]
        node = next((entry['model'] for entry in reversed(node['entries'])
                     if value >= entry['threshold']), node['fallback'])
    assert node['type'] == 'minecraft:model'
    return node


class RobotChargeVisualTests(unittest.TestCase):
    def test_java_color_and_percent_math(self):
        if not (shutil.which('javac') and shutil.which('java')):
            self.skipTest('JDK required for compiling the display-math fixture')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = {
                BASE.removeprefix('src/main/java/') + 'client/RobotBatteryVisuals.java':
                    (SHARED / BASE / 'client/RobotBatteryVisuals.java').read_text(),
                'net/minecraft/world/item/ItemStack.java':
                    'package net.minecraft.world.item; public record ItemStack(int energy) {}',
                'buildcraft/robotics/item/ItemRobot.java':
                    'package buildcraft.robotics.item; import net.minecraft.world.item.ItemStack; '
                    'public final class ItemRobot { public static int getEnergy(ItemStack s) { return s.energy(); } }',
                'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java':
                    'package buildcraft.robotics.internal.legacy.robots; public abstract class EntityRobotBase '
                    '{ public static final int MAX_ENERGY = 100000; }',
                'TestBattery.java': '''
                    import buildcraft.robotics.client.RobotBatteryVisuals;
                    import net.minecraft.world.item.ItemStack;
                    public class TestBattery {
                        static void check(boolean b) { if (!b) throw new AssertionError(); }
                        public static void main(String[] args) {
                            check(RobotBatteryVisuals.powerModel(new ItemStack(0)) == 0F);
                            check(RobotBatteryVisuals.powerModel(new ItemStack(-1)) == 0F);
                            check(RobotBatteryVisuals.powerModel(new ItemStack(1)) == 1F);
                            check(RobotBatteryVisuals.sideTint(new ItemStack(0)) == 0xff000000);
                            check(RobotBatteryVisuals.sideTint(new ItemStack(25000)) == 0xff404040);
                            check(RobotBatteryVisuals.sideTint(new ItemStack(50000)) == 0xff808080);
                            check(RobotBatteryVisuals.sideTint(new ItemStack(100000)) == 0xffffffff);
                            check(RobotBatteryVisuals.sideTint(new ItemStack(200000)) == 0xffffffff);
                            check(RobotBatteryVisuals.itemColor(new ItemStack(0), 1) == 0xffffffff);
                            check(RobotBatteryVisuals.energyFromPercent(50) == 50000);
                            check(RobotBatteryVisuals.energyFromPercent(100) == 100000);
                            check(RobotBatteryVisuals.energyFromPercent(-5) == 0);
                            int previous = 0;
                            for (int p = 0; p <= 100; p++) {
                                int value = RobotBatteryVisuals.brightness(RobotBatteryVisuals.energyFromPercent(p));
                                check(value >= previous);
                                previous = value;
                            }
                        }
                    }
                ''',
            }
            for name, text in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding='utf-8')
            subprocess.run(['javac', '-d', str(root), *(str(root / name) for name in sources)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', str(root), 'TestBattery'], check=True, capture_output=True)

    def test_all_generated_item_dispatches(self):
        props = load_properties()
        original = load(SHARED / MODEL / 'item/robot.json')
        # The board indices are append-only and must not be changed by charge handling.
        profession_entries = [e for e in original['overrides'] if BOARD in e['predicate'] and POWER in e['predicate']]
        self.assertEqual(20, len(profession_entries))
        boards = [(0.0, 'empty')] + [(e['predicate'][BOARD], e['model'].rsplit('/', 1)[-1]) for e in profession_entries]
        self.assertEqual([i / 100 for i in range(21)], [value for value, _ in boards])
        resources = [MODEL+'robot.json', MODEL+'robot_off.json', MODEL+'item/robot.json', ASSETS+'items/robot.json']
        resources += [str(p.relative_to(SHARED)) for p in (SHARED / MODEL / 'item/robot').rglob('*.json')]
        for target in (*OLD, *MODERN):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                layout = target_layout(target, props)
                minecraft = props[f'target.{target}.deps.minecraft']
                for relative in resources:
                    src = resolve_effective_source(layout, props, relative)
                    if src is None:
                        continue
                    dest = root / relative
                    _materialize_text_file(src, dest, logical_relative=relative, minecraft=minecraft,
                                           family=layout.family, platform=layout.platform, preprocess=True)
                # This also installs resource-src/1.21.X/1.21.11, which is not an ordinary source layer.
                generate_target_resources(root, minecraft=minecraft, family=layout.family, platform=layout.platform)
                definition = load(root / MODEL / 'item/robot.json') if target in OLD else load(root / ASSETS / 'items/robot.json')['model']
                powered_parent = load(root / MODEL / 'robot.json')
                off_parent = load(root / MODEL / 'robot_off.json')
                self.assertEqual([powered_parent['elements'][0]], off_parent['elements'])
                self.assertNotIn('textures', off_parent)
                for face, entry in powered_parent['elements'][1]['faces'].items():
                    if face == 'down': self.assertNotIn('tintindex', entry)
                    else: self.assertEqual(0, entry['tintindex'])
                for board, name in boards:
                    for power in (0, 1):
                        properties = {BOARD: board, POWER: power}
                        if target in OLD:
                            model = legacy_model(definition, properties)
                        else:
                            node = modern_model(definition, properties)
                            model = node['model']
                            self.assertEqual([{'type': CHARGE}] if power else [], node.get('tints', []))
                        expected = 'buildcraftrobotics:item/robot/' + ('' if power else 'off/') + name
                        self.assertEqual(expected, model)
                        child = load(root / MODEL / (model.split(':', 1)[1] + '.json'))
                        self.assertEqual('buildcraftrobotics:robot' if power else 'buildcraftrobotics:robot_off', child['parent'])
                        self.assertNotIn('_active', child['textures']['all'])

    def test_generated_client_registration_and_preview(self):
        for target in (*OLD, *MODERN):
            with self.subTest(target=target):
                path = BASE + ('client/FabricRoboticsClient.java' if target == '26.3-fabric' else 'BCRobotics.java')
                client = generated(target, path)
                self.assertIn('"robot_power"', client)
                self.assertIn('RobotBatteryVisuals.powerModel(stack)', client)
                tint = generated(target, BASE + 'client/RobotChargeItemTintSource.java')
                if target in OLD:
                    self.assertIn('RegisterColorHandlersEvent.Item event', client)
                    self.assertIn('RobotBatteryVisuals::itemColor', client)
                    self.assertIsNone(tint)
                else:
                    self.assertIn('"robot_charge"', client)
                    self.assertIn('RobotChargeItemTintSource.MAP_CODEC', client)
                    self.assertIn('RobotPowerProperty.MAP_CODEC', client)
                    self.assertIn('RobotBatteryVisuals.sideTint(stack)', tint)
                    portrait = generated(target, BASE + 'client/render/preview/RobotProcessorPreview.java')
                    self.assertIn('if (RobotBatteryVisuals.powered(stack))', portrait)
                    self.assertIn('RobotBatteryVisuals.sideTint(stack)', portrait)
                    if target == '26.3-fabric':
                        self.assertIn('PictureInPictureRendererRegistry.register(', client)
                gui = generated(target, BASE + 'gui/GuiRobotProcessor.java')
                self.assertIn('RobotBatteryVisuals.energyFromPercent(clamped)', gui)
                self.assertIn('robot.board(), robot.charge()', gui)
                self.assertIn('selected.board(), selected.charge()', gui)
                self.assertNotIn('EntityRobotBase.MAX_ENERGY));', gui)
                renderer = generated(target, BASE + 'client/render/RenderRobot.java')
                self.assertTrue('&& state.energy > 0' in renderer or '&& robot.getEnergyForRendering() > 0' in renderer)


if __name__ == '__main__':
    unittest.main()
