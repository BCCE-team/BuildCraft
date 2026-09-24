#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_layout import load_properties, materialize_target


class ResourcePipeline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bc-resource-pipeline-")
        cls.props = load_properties()
        cls.roots: dict[str, Path] = {}
        for target in (
            "1.19.2-forge",
            "1.20.1-forge",
            "1.21.1-neoforge",
            "1.21.11-neoforge",
        ):
            root = Path(cls.temp.name) / target
            materialize_target(target, root, cls.props)
            cls.roots[target] = root / "src/main/resources"

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def read_json(self, target: str, rel: str):
        return json.loads((self.roots[target] / rel).read_text(encoding="utf-8"))

    def test_target_resource_overlays_are_escape_hatches_only(self):
        expected = {
            "1.19.2-forge": 0,
            "1.20.1-forge": 0,
            "1.21.1-neoforge": 0,
            "1.21.11-neoforge": 0,
        }
        for target, count in expected.items():
            root = ROOT / "version-src" / target / "src/main/resources"
            actual = sum(1 for path in root.rglob("*") if path.is_file()) if root.exists() else 0
            self.assertEqual(count, actual, target)

        forbidden = (
            "data/forge/tags/fluids",
            "assets/buildcraftenergy/models/item/ic2_cell",
            "META-INF/mods.toml",
        )
        for target in ("1.19.2-forge", "1.20.1-forge"):
            root = ROOT / "version-src" / target / "src/main/resources"
            for rel in forbidden:
                self.assertFalse((root / rel).exists(), f"{target}: generated/central resource leaked into overlay: {rel}")

    def test_legacy_energy_tags_are_generated_with_version_specific_ic2_aliases(self):
        bases = (
            "fuel_dense", "fuel_gaseous", "fuel_light", "fuel_mixed_heavy", "fuel_mixed_light",
            "oil", "oil_dense", "oil_distilled", "oil_heavy", "oil_residue",
        )
        for target in ("1.19.2-forge", "1.20.1-forge"):
            root = self.roots[target] / "data/forge/tags/fluids"
            files = sorted(root.glob("*.json"))
            self.assertEqual(30, len(files), target)
            for base in bases:
                for prefix in ("", "hot_", "searing_"):
                    self.assertTrue((root / f"{prefix}{base}.json").is_file())

        old = self.read_json("1.19.2-forge", "data/forge/tags/fluids/oil.json")["values"]
        new = self.read_json("1.20.1-forge", "data/forge/tags/fluids/oil.json")["values"]
        self.assertIn({"id": "ic2:oil", "required": False}, old)
        self.assertIn({"id": "ic2:flowing_oil_cool", "required": False}, old)
        self.assertEqual(["buildcraftenergy:oil", "buildcraftenergy:oil_flowing"], new)

    def test_ic2_cell_models_are_generated_only_for_119(self):
        rel = Path("assets/buildcraftenergy/models/item/ic2_cell")
        old = self.roots["1.19.2-forge"] / rel
        new = self.roots["1.20.1-forge"] / rel
        self.assertEqual(30, len(list(old.glob("*.json"))))
        self.assertFalse(new.exists())
        sample = json.loads((old / "oil_heat_2.json").read_text(encoding="utf-8"))
        self.assertEqual("forge:fluid_container", sample["loader"])
        self.assertEqual("buildcraftenergy:oil_heat_2", sample["fluid"])
        self.assertEqual("buildcraftenergy:items/ic2_cell_fluid", sample["textures"]["fluid"])

    def test_canonical_atlas_is_modern_and_120_gets_only_legacy_compat_additions(self):
        canonical = ROOT / "source-families/modern/src/main/resources/assets/minecraft/atlases/blocks.json"
        self.assertTrue(canonical.is_file())
        def resources(target: str) -> set[str]:
            data = self.read_json(target, "assets/minecraft/atlases/blocks.json")
            return {
                source["resource"] for source in data["sources"]
                if isinstance(source, dict) and isinstance(source.get("resource"), str)
            }

        legacy = resources("1.20.1-forge")
        modern = resources("1.21.1-neoforge")
        self.assertIn("buildcraftcompat:pipes/propolis", legacy)
        self.assertIn("buildcraftcompat:pipes/propolis_itemstack", legacy)
        self.assertNotIn("buildcraftcompat:pipes/propolis", modern)
        for value in (
            "buildcrafttransport:pipes/fe_flow",
            "buildcraftenergy:blocks/mj_dynamo/front",
            "buildcraftlib:model/led_fallback",
        ):
            self.assertIn(value, legacy)
            self.assertIn(value, modern)

    def test_oil_worldgen_is_newest_source_with_a_deterministic_119_downport(self):
        canonical = ROOT / "source-families/modern/src/main/resources/data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json"
        self.assertTrue(canonical.is_file())
        old = self.read_json("1.19.2-forge", "data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json")
        new = self.read_json("1.20.1-forge", "data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json")
        old_settings = old["feature"]["config"]["oilStructureSetting"]
        new_settings = new["feature"]["config"]["oilStructureSetting"]
        self.assertNotIn("enableOilSpouts", old_settings)
        self.assertTrue(new_settings["enableOilSpouts"])
        self.assertEqual("count", old["placement"][0]["type"])
        self.assertEqual("minecraft:count", new["placement"][0]["type"])
        for key in ("smallOilGenProb", "mediumOilGenProb", "largeOilGenProb"):
            self.assertEqual(old_settings[key], new_settings[key])

    def test_special_modern_item_definitions_are_versioned_resource_sources(self):
        special = (
            "assets/buildcraftbuilders/items/blueprint.json",
            "assets/buildcraftbuilders/items/marker_construction.json",
            "assets/buildcraftbuilders/items/schematic_single.json",
            "assets/buildcraftbuilders/items/template.json",
            "assets/buildcraftcore/items/list.json",
            "assets/buildcraftcore/items/map_location.json",
            "assets/buildcraftrobotics/items/redstone_board.json",
            "assets/buildcraftrobotics/items/robot.json",
            "assets/buildcraftsilicon/items/gate_copier.json",
        )
        root = ROOT / "resource-src/modern/1.21.11"
        for rel in special:
            maintained = root / rel
            self.assertTrue(maintained.is_file(), rel)
            # Resource variants are real resources, not JSON polluted by source
            # selector comments.
            json.loads(maintained.read_text(encoding="utf-8"))
            self.assertFalse((self.roots["1.21.1-neoforge"] / rel).exists(), rel)
            item = self.read_json("1.21.11-neoforge", rel)
            self.assertEqual("minecraft:range_dispatch", item["model"]["type"])

    def test_all_versioned_json_resources_are_valid_json(self):
        root = ROOT / "resource-src"
        resources = list(root.rglob("*.json"))
        self.assertGreater(len(resources), 0)
        for path in resources:
            with self.subTest(path=path.relative_to(ROOT).as_posix()):
                json.loads(path.read_text(encoding="utf-8"))

    def test_versioned_binary_resource_leaves_modern_target_overlay(self):
        modern_rel = "assets/buildcrafttransport/textures/pipes/overlay_stained.png"
        modern_source = ROOT / "resource-src/modern/1.21.11" / modern_rel
        self.assertEqual(modern_source.read_bytes(), (self.roots["1.21.11-neoforge"] / modern_rel).read_bytes())

    def test_mechanical_modern_item_definitions_remain_generated(self):
        items_121 = list((self.roots["1.21.1-neoforge"] / "assets").glob("buildcraft*/items/**/*.json"))
        items_12111 = list((self.roots["1.21.11-neoforge"] / "assets").glob("buildcraft*/items/**/*.json"))
        self.assertEqual(0, len(items_121))
        self.assertGreater(len(items_12111), 200)
        example = self.read_json("1.21.11-neoforge", "assets/buildcraftcore/items/wrench.json")
        self.assertEqual("minecraft:model", example["model"]["type"])
        self.assertEqual("buildcraftcore:item/wrench", example["model"]["model"])

    def test_forge_metadata_has_one_maintained_owner(self):
        canonical = ROOT / "source-family-platforms/legacy/forge/src/main/resources/META-INF/mods.toml"
        self.assertTrue(canonical.is_file())
        self.assertFalse((ROOT / "version-src/1.19.2-forge/src/main/resources/META-INF/mods.toml").exists())
        self.assertFalse((ROOT / "version-src/1.20.1-forge/src/main/resources/META-INF/mods.toml").exists())
        old = (self.roots["1.19.2-forge"] / "META-INF/mods.toml").read_text(encoding="utf-8")
        new = (self.roots["1.20.1-forge"] / "META-INF/mods.toml").read_text(encoding="utf-8")
        self.assertIn('modId="ic2"', old)
        self.assertIn("Forestry Apiarist's Pipe", old)
        self.assertNotIn('modId="ic2"', new)
        self.assertIn("Forestry Propolis Item Pipe", new)


if __name__ == "__main__":
    unittest.main(verbosity=2)
