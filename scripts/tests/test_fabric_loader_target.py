from __future__ import annotations

import json
import tempfile
from pathlib import Path
import unittest
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from source_layout import ROOT, load_properties, materialize_target, target_layout


class FabricLoaderTargetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.props = load_properties()
        cls.target = "1.20.1-fabric"

    def test_registry_declares_skeleton_target(self) -> None:
        self.assertEqual("skeleton", self.props[f"target.{self.target}.build.profile"])
        self.assertEqual("legacy", self.props[f"target.{self.target}.source.family"])
        self.assertEqual("fabric", self.props[f"target.{self.target}.source.platform"])
        self.assertEqual("1.20.1", self.props[f"target.{self.target}.deps.minecraft"])
        self.assertEqual("~1.20.1", self.props[f"target.{self.target}.minecraft.version_range"])
        self.assertEqual("1.7.4", self.props[f"target.{self.target}.deps.fabric_loom"])


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

    def test_fabric_build_is_loader_only(self) -> None:
        adapter = (ROOT / "build-logic/loaders/fabric-target.gradle").read_text(encoding="utf-8")
        for token in (
            "java.include 'buildcraft/api/v2/**'",
            "java.include 'buildcraft/fabric/**'",
            "mappings loom.officialMojangMappings()",
            "accessWidenerPath = targetAccessWidener",
            "tasks.named('remapJar')",
            "resources.include 'fabric.mod.json'",
            "resources.include 'buildcraft.accesswidener'",
            "resources.include 'buildcraft.fabric.mixins.json'",
        ):
            self.assertIn(token, adapter)
        for forbidden in ("TilePipeHolder", "PipeFlowItems", "Robot", "TileBuilder"):
            self.assertNotIn(forbidden, adapter)

    def test_materialized_metadata_and_loader_assets(self) -> None:
        with tempfile.TemporaryDirectory(prefix="bc-fabric-target-") as temp:
            root = materialize_target(self.target, Path(temp), self.props)
            resources = root / "src/main/resources"
            metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
            self.assertEqual("buildcraftlib", metadata["id"])
            self.assertTrue(metadata["custom"]["buildcraft:skeleton"])
            self.assertNotIn("provides", metadata, "skeleton must not advertise gameplay module aliases before they are initialized")
            self.assertIn("buildcraft.fabric.BuildCraftFabric", metadata["entrypoints"]["main"])
            self.assertIn("buildcraft.fabric.BuildCraftFabricClient", metadata["entrypoints"]["client"])
            self.assertTrue((resources / "buildcraft.accesswidener").is_file())
            self.assertTrue((resources / "buildcraft.fabric.mixins.json").is_file())


if __name__ == "__main__":
    unittest.main()
