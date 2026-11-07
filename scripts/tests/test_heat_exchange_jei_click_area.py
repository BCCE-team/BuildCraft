"""Heat Exchanger GUI: no vanilla labels; JEI click zone on each loader target.

The JEI click rectangle deliberately matches the central Help Tab hover region in
BCU. It must not intercept fluid tank or container slot interactions.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge",
    "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)
SCREEN = "src/main/java/buildcraft/factory/client/gui/ScreenHeatExchange.java"
PLUGIN = "src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java"
JEI_RECT = (73, 36, 30, 21)
TANK_RECTS = ((44, 64, 34, 17), (116, 43, 16, 38),
              (44, 12, 16, 38), (98, 12, 34, 17))
CONTAINER_RECTS = ((8, 23, 18, 18), (8, 64, 18, 18),
                   (152, 12, 18, 18), (152, 54, 18, 18))


def overlaps(a, b):
    x, y, w, h = a
    xx, yy, ww, hh = b
    return x < xx + ww and xx < x + w and y < yy + hh and yy < y + h


class HeatExchangeJeiGuiRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.code = {}
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            src = {}
            for label, path in (("screen", SCREEN), ("plugin", PLUGIN)):
                effective = resolve_effective_source(layout, props, path)
                assert effective is not None, (target, path)
                src[label] = effective.read_text(encoding="utf-8")
            cls.code[target] = src

    def test_all_targets_register_jei_recipe_click_area(self):
        expected = "registration.addRecipeClickArea(ScreenHeatExchange.class, 73, 36, 30, 21, HEAT_EXCHANGE);"
        for target, docs in self.code.items():
            with self.subTest(target=target):
                self.assertIn("import buildcraft.factory.client.gui.ScreenHeatExchange;", docs["plugin"])
                self.assertEqual(docs["plugin"].count(expected), 1)
                self.assertIn('"heat_exchange", HeatExchangeRecipeView.class', docs["plugin"])

    def test_jei_area_is_only_central_process_and_matches_help(self):
        for area in TANK_RECTS + CONTAINER_RECTS:
            with self.subTest(area=area):
                self.assertFalse(overlaps(JEI_RECT, area))
        for target, docs in self.code.items():
            with self.subTest(target=target):
                self.assertIn('GuiHelpUtil.addRoot(mainGui, 73, 36, 30, 21,', docs['screen'])
                self.assertIn('renderHeatSchematic(', docs['screen'])

    def test_title_and_inventory_labels_hidden_without_losing_help(self):
        for target, docs in self.code.items():
            with self.subTest(target=target):
                screen = docs['screen']
                method = 'extractLabels' if target.startswith('26.') else 'renderLabels'
                self.assertIn('protected void '+method+'(', screen)
                self.assertNotIn('super.'+method+'(', screen)
                self.assertNotIn('titleLabelY -= 3;', screen)
                self.assertNotIn('inventoryLabelY += 8;', screen)
                self.assertIn('mainGui.preDrawForeground(', screen)
                self.assertIn('mainGui.drawElementForegrounds(', screen)
                self.assertIn('mainGui.postDrawForeground(', screen)
                self.assertIn('mainGui.shownElements.add(new LedgerHelp(mainGui, false))', screen)
                self.assertEqual(screen.count('add(new HeatExchangeTankComponent('), 4)


if __name__ == '__main__':
    unittest.main()
