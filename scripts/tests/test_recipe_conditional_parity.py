"""Keep Forestry's conditional Apiarist's Pipe recipe in cross-target parity.

The old Forge recipes wrap the real result; NeoForge 1.21.1 uses a direct
recipe with an optional-mod condition. Both must yield eight of the same pipe.
"""
from __future__ import annotations

import json
import runpy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))


class ForestryConditionalRecipeParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Load the exact production validator without running all its unrelated
        # gameplay assertions. Its TARGETS are real materialized source trees.
        cls.validator = runpy.run_path(str(ROOT / "scripts/validate-behavior-parity.py"))

    def test_propolis_recipe_outputs_are_identical_across_all_three_targets(self):
        inventory = self.validator["recipe_inventory"]
        reference = inventory("1.19.2-forge")
        self.assertEqual(reference["buildcraftcompat:pipe_item_propolis"],
                         ("buildcraftcompat:pipe_item_propolis", 8))
        for target in ("1.20.1-forge", "1.21.1-neoforge"):
            with self.subTest(target=target):
                self.assertEqual(inventory(target), reference)
        self.assertEqual(self.validator["ERRORS"], [])

    def test_mod_loaded_conditions_remain_optional_on_each_loader(self):
        paths = self.validator["TARGETS"]
        for target in paths:
            name = "recipe" if target == "1.21.1-neoforge" else "recipes"
            file = paths[target] / f"src/main/resources/data/buildcraftcompat/{name}/pipe_item_propolis.json"
            doc = json.loads(file.read_text(encoding="utf-8"))
            with self.subTest(target=target):
                if target == "1.21.1-neoforge":
                    self.assertEqual(doc["neoforge:conditions"],
                                     [{"type": "neoforge:mod_loaded", "modid": "forestry"}])
                    recipe = doc
                else:
                    self.assertEqual(doc["type"], "forge:conditional")
                    self.assertEqual(len(doc["recipes"]), 1)
                    branch = doc["recipes"][0]
                    self.assertEqual(branch["conditions"],
                                     [{"type": "forge:mod_loaded", "modid": "forestry"}])
                    recipe = branch["recipe"]
                self.assertEqual(recipe["result"],
                                 {"item": "buildcraftcompat:pipe_item_propolis", "count": 8})

    def test_malformed_conditional_recipe_fails_closed(self):
        unwrap = self.validator["unwrap_conditional_recipe"]
        errors = self.validator["ERRORS"]
        for document in (
            {"type": "forge:conditional", "recipes": []},
            {"type": "forge:conditional", "recipes": [{"recipe": {"result": "minecraft:stone"}},
                                                        {"recipe": {"result": "minecraft:dirt"}}]},
            {"type": "forge:conditional", "recipes": [{"recipe": "invalid"}]},
        ):
            with self.subTest(document=document):
                previous = len(errors)
                self.assertIsNone(unwrap("1.19.2-forge", "buildcraftcompat:pipe_item_propolis", document))
                self.assertEqual(len(errors), previous + 1)
        errors.clear()


if __name__ == "__main__":
    unittest.main()
