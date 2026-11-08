"""Source-layer regression for the BCU Heat Exchanger GUI and safe BCCE backend port.

Gameplay (fluid conservation and repair) remains covered separately by the
existing heat-exchange/topology GameTests, which must run inside Minecraft.
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
    "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)
TILE = "src/main/java/buildcraft/factory/tile/TileHeatExchange.java"
MENU = "src/main/java/buildcraft/factory/client/gui/MenuHeatExchange.java"
SCREEN = "src/main/java/buildcraft/factory/client/gui/ScreenHeatExchange.java"
FLUID = "src/main/java/buildcraft/lib/misc/FluidUtilBC.java"


class HeatExchangeBcuGuiParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        props = load_properties()
        cls.texts = {}
        for target in TARGETS:
            layout = target_layout(target, props)
            docs = {}
            for label, path in [("tile", TILE), ("menu", MENU), ("screen", SCREEN), ("fluid", FLUID)]:
                source = resolve_effective_source(layout, props, path)
                assert source is not None, (target, path)
                docs[label] = source.read_text(encoding="utf-8")
            cls.texts[target] = docs

    def test_all_versions_expose_four_container_slots_in_bcu_order(self):
        positions = ((0, 8, 23), (1, 8, 64), (2, 152, 12), (3, 152, 54))
        for target, docs in self.texts.items():
            with self.subTest(target=target):
                menu = docs["menu"]
                for slot, x, y in positions:
                    self.assertIn(f"new SlotItemHandler(fluidItems, {slot}, {x}, {y})", menu)
                self.assertLess(menu.index("new SlotItemHandler(fluidItems, 0"), menu.index("for (int i = 0; i < 3; ++i)"))
                self.assertIn("addDataSlot(bg);", menu)
                self.assertIn("this.progressData = bg;", menu)

    def test_all_versions_sync_and_persist_progress_and_containers(self):
        for target, docs in self.texts.items():
            with self.subTest(target=target):
                tile = docs["tile"]
                self.assertIn("new ItemHandlerSimple(4, 1)", tile)
                self.assertIn('"containerSlots"', tile)
                self.assertIn('"progressState"', tile)
                self.assertIn('"progress"', tile)
                self.assertIn("start.getProgressState().ordinal() * 128", tile)
                self.assertIn("processContainerSlots();", tile)
                self.assertIn("public TileHeatExchange findStart()", tile)
                self.assertIn("controller.canPlayerEdit(player)", tile)
                self.assertIn("!level.hasChunkAt(candidate)", tile)
                self.assertIn("if (h_diff < 1 || c_diff < 1)", tile)
                self.assertNotIn('throw new IllegalStateException("Invalid recipe "', tile)

    def test_gui_uses_physical_tank_positions_not_data_order(self):
        # TankContainerData order must remain START.in, START.out, END.in, END.out.
        tanks = (
            "new HeatExchangeTankComponent(44, 64, 34, 17)",
            "new HeatExchangeTankComponent(116, 43, 16, 38)",
            "new HeatExchangeTankComponent(44, 12, 16, 38)",
            "new HeatExchangeTankComponent(98, 12, 34, 17)",
        )
        for target, docs in self.texts.items():
            with self.subTest(target=target):
                text = docs["screen"]
                matches = [text.index(part) for part in tanks]
                self.assertEqual(matches, sorted(matches))
                if target.startswith("26."):
                    self.assertIn("super(menu, inventory, name, 4, TEXTURE_BASE, 176, 171)", text)
                else:
                    self.assertIn("this.imageHeight = 171", text)
                self.assertIn("WIPE_WIDTH = 54, WIPE_HEIGHT = 71", text)
                self.assertIn("renderHeatSchematic(", text)
                self.assertEqual(text.count("GuiHelpUtil.addRoot(mainGui"), 5)
                for detail in ("hot_in", "cold_in", "heated_out", "cooled_out", "progress"):
                    self.assertIn("buildcraft.help.heat_exchange."+detail+".title", text)

    def test_container_transfer_remains_conservative_and_copy_based(self):
        for target, docs in self.texts.items():
            with self.subTest(target=target):
                code = docs["fluid"]
                self.assertIn("transferHeatExchangeContainer(", code)
                self.assertIn("initial.getCount() != 1", code)
                self.assertIn("initial.copy()", code)
                self.assertIn("if (moved != null && !moved.isEmpty())", code)
                self.assertIn("handler.getContainer().copy()", code)
                self.assertIn("move(handler, tank, 1000)", code)
                self.assertIn("move(tank, handler, 1000)", code)

    def test_english_help_keys_exist(self):
        lang = ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json"
        data = json.loads(lang.read_text(encoding="utf-8"))
        for kind in ("hot_in", "cold_in", "heated_out", "cooled_out"):
            for suffix in ("title", "desc"):
                self.assertTrue(data.get(f"buildcraft.help.heat_exchange.{kind}.{suffix}"))


if __name__ == "__main__":
    unittest.main()
