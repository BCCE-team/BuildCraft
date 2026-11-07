"""JEI shortcuts for all crafting stations that expose recipes in BCCE.

Run: python -m unittest scripts.tests.test_crafting_station_jei_clicks -v
The rectangles are native JEI hitboxes (GUI-relative), not mouse interception.
"""
from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, resolve_effective_source, target_layout

TARGETS = (
    '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
    '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)
PLUGIN = 'src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java'
# Safe clickable regions measured against the machine sprites and GUI slot layouts.
AREAS = {
    'GuiAssemblyTable': (83, 35, 10, 72, 'ASSEMBLY'),
    'GuiProgrammingTable': (160, 35, 12, 72, 'PROGRAMMING'),
    'GuiIntegrationTable': (160, 21, 12, 72, 'INTEGRATION'),
    'GuiAdvancedCraftingTable': (93, 32, 23, 16, 'RecipeTypes.CRAFTING'),
    'GuiAutoCraftItems': (90, 47, 23, 10, 'RecipeTypes.CRAFTING'),
}
# Conservative exclusion masks: known slots and interactive controls.
SLOTS = {
    'GuiAssemblyTable': [(8 + 18 * x, 36 + 18 * y, 16, 16) for x in range(3) for y in range(4)]
                        + [(116 + 18 * x, 36 + 18 * y, 16, 16) for x in range(3) for y in range(4)],
    'GuiProgrammingTable': [(8, 36, 16, 16), (8, 90, 16, 16)]
                           + [(43 + 18 * x, 36 + 18 * y, 16, 16) for x in range(6) for y in range(4)]
                           + [(43, 23, 17, 12), (142, 23, 17, 12)],
    'GuiIntegrationTable': [(138, 49, 16, 16), (101, 36, 16, 16)],
    'GuiAdvancedCraftingTable': [(33 + 18 * x, 16 + 18 * y, 16, 16) for x in range(3) for y in range(3)]
                                + [(127, 33, 16, 16)]
                                + [(109 + 18 * x, 85 + 18 * y, 16, 16) for x in range(3) for y in range(3)],
    'GuiAutoCraftItems': [(30 + 18 * x, 17 + 18 * y, 16, 16) for x in range(3) for y in range(3)]
                         + [(93, 27, 16, 16), (124, 35, 16, 16)],
}
GUI = {
    'GuiAssemblyTable': 'buildcraft/silicon/gui/GuiAssemblyTable.java',
    'GuiProgrammingTable': 'buildcraft/silicon/gui/GuiProgrammingTable.java',
    'GuiIntegrationTable': 'buildcraft/silicon/gui/GuiIntegrationTable.java',
    'GuiAdvancedCraftingTable': 'buildcraft/silicon/gui/GuiAdvancedCraftingTable.java',
    'GuiAutoCraftItems': 'buildcraft/factory/gui/GuiAutoCraftItems.java',
}

def intersects(a, b):
    x, y, w, h = a
    xx, yy, ww, hh = b
    return x < xx + ww and xx < x + w and y < yy + hh and yy < y + h

class CraftingStationJeiShortcuts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {}
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            plugin_path = resolve_effective_source(layout, props, PLUGIN)
            assert plugin_path is not None, target
            cls.sources[target] = {
                'plugin': plugin_path.read_text(encoding='utf-8'),
                'gui': {
                    name: resolve_effective_source(layout, props, 'src/main/java/' + path)
                    for name, path in GUI.items()
                },
            }

    def test_each_target_registers_every_available_recipe_station_once(self):
        for target, docs in self.sources.items():
            plugin = docs['plugin']
            with self.subTest(target=target):
                self.assertEqual(plugin.count('void registerGuiHandlers('), 1)
                for name, (x, y, w, h, category) in AREAS.items():
                    expected = f'registration.addRecipeClickArea({name}.class, {x}, {y}, {w}, {h}, {category});'
                    self.assertEqual(plugin.count(expected), 1, f'{target}: {expected}')
                    self.assertEqual(plugin.count(f'addRecipeClickArea({name}.class'), 1, f'{target}: duplicate {name}')
                    self.assertIn('import ' + ('buildcraft.factory.gui.' if name == 'GuiAutoCraftItems' else 'buildcraft.silicon.gui.') + name + ';', plugin)
                    self.assertIsNotNone(docs['gui'][name], f'{target}: {name} missing from materialization')
                self.assertIn('registration.addRecipeClickArea(ScreenHeatExchange.class, 73, 36, 30, 21, HEAT_EXCHANGE);', plugin)
                self.assertNotIn('addRecipeClickArea(GuiEngineIron_BC8.class', plugin)

    def test_jei_shortcuts_do_not_cover_machine_slots_or_page_controls(self):
        for name, (x, y, w, h, _) in AREAS.items():
            with self.subTest(machine=name):
                self.assertGreater(w, 0)
                self.assertGreater(h, 0)
                self.assertGreaterEqual(x, 0)
                self.assertGreaterEqual(y, 0)
                self.assertLessEqual(x + w, 176)
                self.assertLessEqual(y + h, 241)
                for slot in SLOTS[name]:
                    self.assertFalse(intersects((x, y, w, h), slot), (name, slot))

    def test_power_bar_shortcuts_cover_actual_progress_gauges(self):
        gauges = {
            'GuiAssemblyTable': (86, 36, 4, 70),
            'GuiProgrammingTable': (164, 36, 4, 70),
            'GuiIntegrationTable': (164, 22, 4, 70),
        }
        for target, docs in self.sources.items():
            for name, (gx, gy, gw, gh) in gauges.items():
                with self.subTest(target=target, machine=name):
                    area = AREAS[name][:4]
                    self.assertLessEqual(area[0], gx)
                    self.assertLessEqual(area[1], gy)
                    self.assertGreaterEqual(area[0] + area[2], gx + gw)
                    self.assertGreaterEqual(area[1] + area[3], gy + gh)
                    text = docs['gui'][name].read_text(encoding='utf-8')
                    self.assertRegex(text, r'RECT_PROGRESS\s*=\s*new GuiRectangle\('+f'{gx}, {gy}, {gw}, {gh}'+r'\)')

    def test_advanced_crafting_uses_the_bcu_arrow_and_autoworkbench_progress(self):
        self.assertEqual(AREAS['GuiAdvancedCraftingTable'][:4], (93, 32, 23, 16))
        self.assertEqual(AREAS['GuiAutoCraftItems'][:4], (90, 47, 23, 10))
        for target, docs in self.sources.items():
            with self.subTest(target=target):
                gui = docs['gui']['GuiAutoCraftItems'].read_text(encoding='utf-8')
                self.assertIn('RECT_PROGRESS = new GuiRectangle(90, 47, 23, 10)', gui)
                plugin = docs['plugin']
                for item in ('ASSEMBLY', 'PROGRAMMING', 'INTEGRATION'):
                    self.assertIn('registration.addRecipeCatalyst(', plugin)
                    self.assertIn(', '+item+');', plugin)

if __name__ == '__main__':
    unittest.main()
