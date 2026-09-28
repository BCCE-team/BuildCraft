from __future__ import annotations

import json
import tempfile
from pathlib import Path
import unittest
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from source_layout import ROOT, load_properties, target_layout
from materialize_project import export_standalone_project


class FabricLoaderTargetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.props = load_properties()
        cls.target = "1.20.1-fabric"

    def test_registry_declares_gameplay_parity_target(self) -> None:
        self.assertEqual("gameplay_parity", self.props[f"target.{self.target}.build.profile"])
        self.assertEqual("legacy", self.props[f"target.{self.target}.source.family"])
        self.assertEqual("fabric", self.props[f"target.{self.target}.source.platform"])
        self.assertEqual("1.20.1", self.props[f"target.{self.target}.deps.minecraft"])
        self.assertEqual("~1.20.1", self.props[f"target.{self.target}.minecraft.version_range"])
        self.assertEqual("1.7.4", self.props[f"target.{self.target}.deps.fabric_loom"])
        self.assertEqual("3.0.0", self.props[f"target.{self.target}.deps.energy_api"])


    def test_loom_version_has_one_canonical_source(self) -> None:
        wrapper = (ROOT / "builds/legacy/build.fabric.gradle").read_text(encoding="utf-8")
        settings = (ROOT / "builds/legacy/settings.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("id 'fabric-loom'", wrapper)
        self.assertNotIn("id 'fabric-loom' version", wrapper)
        self.assertIn("deps.fabric_loom", settings)
        self.assertIn('requested.id.id == "fabric-loom"', settings)
        self.assertIn("useVersion(fabricLoomVersion", settings)

    def test_target_overlay_has_no_java(self) -> None:
        layout = target_layout(self.target, self.props)
        self.assertEqual([], list(layout.overlay_root.rglob("*.java")))

    def test_fabric_build_activates_effective_gameplay_source_graph(self) -> None:
        adapter = (ROOT / "build-logic/loaders/fabric-target.gradle").read_text(encoding="utf-8")
        for token in (
            "Fabric adapter expects the Stage 5 gameplay_parity profile",
            "java.setSrcDirs(effectiveDirs('src/main/java'))",
            "resources.setSrcDirs(effectiveDirs('src/main/resources'))",
            "teamreborn:energy:${energyApiVersion}",
            "mappings loom.officialMojangMappings()",
            "accessWidenerPath = targetAccessWidener",
            "tasks.named('remapJar')",
            "'BuildCraft-Fabric-Stage': 'gameplay-parity'",
            "sourceSets.main.java.exclude 'buildcraft/compat/jei/**'",
            "sourceSets.main.java.exclude 'buildcraft/compat/jade/**'",
            "sourceSets.main.java.exclude 'buildcraft/compat/ic2/**'",
            "sourceSets.main.java.exclude 'buildcraft/compat/forestry/**'",
        ):
            self.assertIn(token, adapter)
        for forbidden in (
            "java.include 'buildcraft/fabric/**'",
            "java.include 'buildcraft/lib/platform/**'",
            "resources.include 'fabric.mod.json'",
        ):
            self.assertNotIn(forbidden, adapter)


    def test_materialized_metadata_and_loader_assets(self) -> None:
        with tempfile.TemporaryDirectory(prefix="bc-fabric-target-") as temp:
            root = export_standalone_project(self.target, Path(temp) / "project", self.props)
            resources = root / "src/main/resources"
            metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
            self.assertEqual("buildcraftlib", metadata["id"])
            self.assertTrue(metadata["custom"]["buildcraft:gameplay_parity"])
            self.assertNotIn("buildcraft:server_foundation", metadata["custom"])
            self.assertTrue(metadata["custom"]["buildcraft:module_bootstrap"])
            self.assertEqual(
                {
                    "buildcraftcore", "buildcraftbuilders", "buildcraftenergy", "buildcraftfactory",
                    "buildcraftrobotics", "buildcraftsilicon", "buildcrafttransport",
                },
                set(metadata["provides"]),
            )
            self.assertIn("buildcraft.fabric.BuildCraftFabric", metadata["entrypoints"]["main"])
            self.assertIn("buildcraft.fabric.BuildCraftFabricClient", metadata["entrypoints"]["client"])
            self.assertTrue((resources / "buildcraft.accesswidener").is_file())
            self.assertTrue((resources / "buildcraft.fabric.mixins.json").is_file())

            java_root = root / "src/main/java"
            self.assertGreater(len(list(java_root.rglob("*.java"))), 1000)
            for relative in (
                "buildcraft/transport/pipe/flow/PipeFlowItems.java",
                "buildcraft/transport/pipe/flow/PipeFlowFluids.java",
                "buildcraft/energy/tile/TileEngineFE.java",
                "buildcraft/silicon/tile/TileAssemblyTable.java",
                "buildcraft/robotics/boards/BoardRobotBuilder.java",
            ):
                self.assertTrue((java_root / relative).is_file(), relative)

            for compat in ("jei", "jade", "ic2", "forestry"):
                self.assertFalse((java_root / "buildcraft/compat" / compat).exists(), compat)

            standalone_gradle = (root / "build.gradle").read_text(encoding="utf-8")
            self.assertNotIn("java.include", standalone_gradle)
            self.assertNotIn("resources.include", standalone_gradle)
            self.assertIn("teamreborn:energy:", standalone_gradle)


if __name__ == "__main__":
    unittest.main()
