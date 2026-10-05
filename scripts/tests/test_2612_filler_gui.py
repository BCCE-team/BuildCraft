#!/usr/bin/env python3
"""Regression guard for the 26.1.2 Filler JSON GUI slot bindings."""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_layout import load_properties, materialize_target  # noqa: E402


class FillerGui2612Tests(unittest.TestCase):
    def test_player_inventory_is_bound_before_json_deserialization(self) -> None:
        props = load_properties()
        with tempfile.TemporaryDirectory(prefix="bc-2612-filler-gui-") as tmp:
            out = Path(tmp) / "effective"
            materialize_target("26.1.2-neoforge", out, props)

            gui = (out / "src/main/java/buildcraft/builders/gui/GuiFiller.java").read_text(encoding="utf-8")
            resources = (
                out / "src/main/resources/assets/buildcraftbuilders/gui/filler.json"
            ).read_text(encoding="utf-8")

            binding = 'properties.put("player.inventory", new InventorySlotHolder(container, container.playerInventory));'
            self.assertIn('"slot": "player.inventory"', resources)
            constructor = gui[gui.index("public GuiFiller("):gui.index("protected void preLoad(")]
            pre_load_start = gui.index("protected void preLoad(")
            pre_load = gui[pre_load_start:gui.index("public void containerTick()", pre_load_start)]

            self.assertIn(binding, pre_load)
            self.assertLess(constructor.index("preLoad(jsonGui);"), constructor.index("jsonGui.load();"))


if __name__ == "__main__":
    unittest.main()
