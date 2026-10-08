#!/usr/bin/env python3
"""26.X core contracts against the materialized Java of all three targets."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts/tests"))

from source_config import load_properties, target_layout
from source_layout import effective_source_files, _materialize_text_file
from minecraft_compat_fixture import parse_sources
from core_26_fixture import execute as execute_core_geometry

TARGETS = ("26.1.2", "26.2", "26.3")
CORE = "src/main/java/buildcraft/core"


class Core26EffectiveContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bc-core-26-")
        cls.materialized = {}
        properties = load_properties()
        for version in TARGETS:
            target = version + "-neoforge"
            layout = target_layout(target, properties)
            minecraft = properties[f"target.{target}.deps.minecraft"]
            downport_roots = tuple(root for root in (
                layout.family_downport_root,
                layout.family_platform_downport_root,
            ) if root)
            output = Path(cls.temp.name) / target
            entries = effective_source_files(layout, properties, CORE)
            for relative, source in sorted(entries.items()):
                _materialize_text_file(
                    source, output / relative, logical_relative=relative,
                    minecraft=minecraft, family=layout.family,
                    platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downport_roots),
                )
            cls.materialized[version] = output

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def text(self, version, filename):
        return (self.materialized[version] / CORE / filename).read_text(encoding="utf-8")

    def test_frozen_26_1_2_core_bytes(self):
        manifest = json.loads((ROOT / "build-config/materialized-baselines/26.1.2-neoforge.json").read_text())
        expected = {name: sha for name, sha in manifest["files"].items()
                    if name.startswith(CORE + "/") and name.endswith(".java")}
        actual = {path.relative_to(self.materialized["26.1.2"]).as_posix():
                  hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in (self.materialized["26.1.2"] / CORE).rglob("*.java")}
        self.assertEqual(74, len(expected))
        self.assertEqual(expected, actual, "26.1.2 effective core must be byte-for-byte unchanged")

    def test_new_render_bridge_never_leaks_into_26_1_2(self):
        self.assertFalse((self.materialized["26.1.2"] / CORE / "client/CoreWorldGeometry.java").exists())
        for version in ("26.2", "26.3"):
            with self.subTest(version=version):
                root = self.materialized[version] / CORE
                self.assertEqual(75, len(list(root.rglob("*.java"))))
                bridge = self.text(version, "client/CoreWorldGeometry.java")
                self.assertIn("ExtractLevelRenderStateEvent", bridge)
                self.assertIn("SubmitCustomGeometryEvent", bridge)
                self.assertIn("BCWorldGeometry.capture(() ->", bridge)
                self.assertIn("BCWorldGeometry.submit(layers,", bridge)
                self.assertIn("getRenderData(GEOMETRY)", bridge)
                events = self.text(version, "client/BCCoreClientModEvents.java")
                self.assertIn("NeoForge.EVENT_BUS.addListener(CoreWorldGeometry::extract)", events)
                self.assertIn("NeoForge.EVENT_BUS.addListener(CoreWorldGeometry::submit)", events)
                self.assertNotIn("NeoForge.EVENT_BUS.addListener(RenderTickListener::renderLast)", events)
                self.assertNotIn("NeoForge.EVENT_BUS.addListener(MarkerSubmitRenderer121111::submit)", events)
                self.assertIn("captureWorld(PoseStack", self.text(version, "client/RenderTickListener.java"))
                self.assertIn("public static void capture(ClientLevel", self.text(version, "client/MarkerSubmitRenderer121111.java"))
                volume = self.text(version, "client/render/RenderVolumeBoxes.java")
                self.assertIn("BCWorldGeometry.buffer(RenderCompat.solid())", volume)
                self.assertNotIn("renderBuffers().bufferSource()", volume)

    def test_26_3_recipe_registry_bootstrap_and_downports(self):
        current = self.text("26.3", "BCCoreRecipes.java")
        self.assertIn("BootstrapContext<Recipe<?>>", current)
        self.assertIn("BootstrapContext<Advancement>", current)
        self.assertIn("super(recipes, advancements)", current)
        for version in ("26.1.2", "26.2"):
            with self.subTest(version=version):
                old = self.text(version, "BCCoreRecipes.java")
                self.assertIn("HolderLookup.Provider registries, RecipeOutput output", old)
                self.assertNotIn("BootstrapContext<Recipe<?>>", old)

    def test_26_3_fluid_item_api_replacement(self):
        current = self.text("26.3", "item/ItemFragileFluidContainer.java")
        self.assertNotIn("IFluidHandlerItem", current)
        self.assertNotIn("class FragileFluidHandler", current)
        self.assertIn("FluidDropProvider", current)
        self.assertIn("public static void setFluid(", current)
        self.assertIn("import org.jetbrains.annotations.NotNull;", current)
        for version in ("26.1.2", "26.2"):
            with self.subTest(version=version):
                self.assertIn("class FragileFluidHandler implements IFluidHandlerItem",
                              self.text(version, "item/ItemFragileFluidContainer.java"))
        for trigger in ("TriggerFluidContainer.java", "TriggerFluidContainerLevel.java"):
            current = self.text("26.3", "statements/" + trigger)
            self.assertIn("BuildCraftServices.FLUID_ITEMS", current)
            self.assertIn("FuelApiBridge.stackOf(", current)
            self.assertNotIn("FluidUtil.getFluidContained", current)
            for version in ("26.1.2", "26.2"):
                self.assertIn("FluidUtil.getFluidContained",
                              self.text(version, "statements/" + trigger))

    def test_sdl_keycodes_are_version_specific(self):
        self.assertIn("InputConstants.KEY_ESCAPE", self.text("26.3", "list/GuiList.java"))
        for version in ("26.1.2", "26.2"):
            with self.subTest(version=version):
                self.assertIn("if (a == 256)", self.text(version, "list/GuiList.java"))

    def test_core_geometry_event_lifecycle(self):
        for version in ("26.2", "26.3"):
            with self.subTest(version=version):
                output = execute_core_geometry(
                    self.materialized[version] / CORE / "client/CoreWorldGeometry.java")
                self.assertIn("8 assertions PASS", output)
                print(version + ": " + output, flush=True)

    def test_native_core_java_syntax(self):
        roots = [self.materialized[version] / CORE for version in ("26.2", "26.3")]
        result = parse_sources(roots)
        self.assertEqual(2, result.count("75 units, 0 errors"), result)
        print(result, flush=True)


if __name__ == "__main__":
    unittest.main()
