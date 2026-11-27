"""Held equipment is part of the read-only cargo snapshot on every target."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
           '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')


def effective_java(target, relative, directory):
    props = load_properties()
    layout = target_layout(target, props)
    logical = 'src/main/java/buildcraft/' + relative
    source = resolve_effective_source(layout, props, logical)
    assert source is not None, (target, logical)
    output = directory / logical
    native_roots = tuple(r for r in (layout.family_downport_root,
                                     layout.family_platform_downport_root) if r is not None)
    _materialize_text_file(source, output, logical_relative=logical,
        minecraft=props[f'target.{target}.deps.minecraft'], family=layout.family,
        platform=layout.platform, preprocess=True,
        native_source=any(source.is_relative_to(r) for r in native_roots),
        native_passthrough=layout.platform == 'fabric' and
            source.is_relative_to(layout.family_platform_root))
    return output.read_text(encoding='utf-8')


class HeldCargoTests(unittest.TestCase):
    def test_selected_loaded_robots_send_nonempty_held_stack_before_cargo(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                menu = effective_java(target, 'robotics/container/ContainerRobotProcessor.java', root)
                self.assertIn('import net.minecraft.world.entity.EquipmentSlot;', menu)
                snapshot = menu.split('private void refreshCargoSnapshot() {', 1)[1].split('private void sendCargo(', 1)[0]
                read_hand = 'ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);'
                copy_hand = 'if (!held.isEmpty()) current.add(new CargoEntry(held.copy(), "", 0, 0));'
                self.assertEqual(snapshot.count(read_hand), 1)
                self.assertIn(copy_hand, snapshot)
                self.assertLess(snapshot.index('!processor.isRobotLinked(robotId)'), snapshot.index(read_hand))
                self.assertLess(snapshot.index('if (robot == null || robot.isRemoved())'), snapshot.index(read_hand))
                self.assertLess(snapshot.index(read_hand), snapshot.index(copy_hand))
                self.assertLess(snapshot.index(copy_hand), snapshot.index('robot.getItem(slot)'))
                self.assertLess(snapshot.index('robot.getItem(slot)'), snapshot.index('robot.getFluidInTank(tank)'))
                self.assertEqual(snapshot.count('if (current.size() >= MAX_CARGO_ENTRIES) break;'), 2)
                self.assertIn('sendCargo(robotId, true, current);', snapshot)
                self.assertNotIn('setItemInUse(', snapshot)
                self.assertNotIn('forceRobotChunk(', snapshot)
                self.assertIn('if (requestedCargoRobotId > 0) refreshCargoSnapshot();', menu)

    def test_actual_hand_is_separate_from_transfer_inventory_and_uses_existing_item_renderer(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                robot = effective_java(target, 'robotics/entity/EntityRobot.java', root)
                gui = effective_java(target, 'robotics/gui/GuiRobotProcessor.java', root)
                self.assertIn('if (slot == EquipmentSlot.MAINHAND) {\n            return itemInUse;', robot)
                self.assertIn('return inventory.size();', robot)
                self.assertIn('inventory.get(slot)', robot)
                self.assertIn('renderCargoItem(entry.stack(),', gui)
                self.assertIn('processorMenu.getCargo()', gui)
                self.assertIn('hovered.stack()', gui)
                self.assertIn('cargoOffset', gui)


if __name__ == '__main__':
    unittest.main()
