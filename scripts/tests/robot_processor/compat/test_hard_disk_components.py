"""Keep the HDD explosion-resistance component compatible with each MC generation."""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties
from source_lookup import read_materialized_java

DISK = 'src/main/java/buildcraft/robotics/item/ItemHardDisk.java'


class HardDiskComponentCompatibilityTests(unittest.TestCase):
    def test_explosion_resistance_uses_target_registry_api(self):
        props = load_properties()
        for target in props['targets'].split(','):
            with self.subTest(target=target):
                source = read_materialized_java(target, DISK)
                self.assertIn('properties.stacksTo(1);', source)
                if target.startswith('26.'):
                    self.assertIn('properties.delayedComponent(', source)
                    self.assertIn('registries.getOrThrow(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)', source)
                    self.assertNotIn('properties.component(net.minecraft.core.component.DataComponents.DAMAGE_RESISTANT', source)
                elif target.startswith('1.21.11-'):
                    self.assertIn('properties.component(net.minecraft.core.component.DataComponents.DAMAGE_RESISTANT', source)
                    self.assertIn('DamageResistant(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)', source)
                    self.assertNotIn('delayedComponent(', source)
                else:
                    self.assertNotIn('DataComponents.DAMAGE_RESISTANT', source)
                    self.assertNotIn('DamageTypeTags.IS_EXPLOSION', source)


if __name__ == '__main__':
    unittest.main()
