"""Cross-loader BuildCraft boat-fluid physics contracts and pure Java material behavior."""
from __future__ import annotations

import re
import sys
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

MODEL = ROOT / "source-shared/src/main/java/buildcraft/energy/fluid/BoatFluidMaterial.java"
RUNTIME = ROOT / "source-shared/src/main/java/buildcraft/energy/fluid/BoatFluidPhysics.java"
NEOFORGE = ROOT / "source-platforms/neoforge/src/main/java/buildcraft/energy/BCEnergyFluids.java"
FABRIC = ROOT / "source-family-platforms/26.X/fabric/src/main/java/buildcraft/energy/BCEnergyFluids.java"
OLD_FORGE = [
    ROOT / f"version-src/{version}-forge/src/main/java/buildcraft/energy/BCEnergyFluids.java"
    for version in ("1.19.2", "1.20.1")
]


class BoatFluidPhysicsTests(unittest.TestCase):
    def test_registered_loader_hooks_and_native_boating(self):
        for path in [*OLD_FORGE, NEOFORGE]:
            with self.subTest(path=path):
                content = path.read_text(encoding="utf-8")
                self.assertIn("BoatFluidPhysics.register();", content)
                self.assertIn(".supportsBoating(boilAdjustedDensity > 0)", content)
                self.assertLess(content.index("BoatFluidPhysics.register();"), content.index("private static void defineFluid"))
        fabric = FABRIC.read_text(encoding="utf-8")
        self.assertIn("BoatFluidPhysics.enableCustomBuoyancy();", fabric)
        self.assertLess(fabric.index("BoatFluidPhysics.enableCustomBuoyancy();"),
                        fabric.index("BoatFluidPhysics.register();"))
        for path in [*OLD_FORGE, NEOFORGE]:
            self.assertNotIn("BoatFluidPhysics.enableCustomBuoyancy();", path.read_text(encoding="utf-8"))
        runtime = RUNTIME.read_text(encoding="utf-8")
        self.assertIn("PlatformEvents.entityJoin", runtime)
        self.assertIn("PlatformEvents.levelTick(BCEvents.Phase.END", runtime)
        self.assertIn("PlatformEvents.levelUnload", runtime)
        self.assertIn("if (data == null || data.isGas()) continue;", runtime)
        self.assertNotIn("//? if fabric {", runtime)
        self.assertIn("if (customBuoyancyEnabled && Double.isFinite(surface)", runtime)
        self.assertIn("public static void enableCustomBuoyancy()", runtime)

    def test_fluid_registry_lookup_uses_compatible_minecraft_api(self):
        """1.19.2 uses Registry.FLUID; later versions use BuiltInRegistries.FLUID."""
        properties = load_properties()
        relative = "src/main/java/buildcraft/energy/fluid/BoatFluidPhysics.java"
        targets = (
            "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge",
            "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge",
            "26.3-neoforge", "26.3-fabric",
        )
        for target in targets:
            layout = target_layout(target, properties)
            source = resolve_effective_source(layout, properties, relative)
            self.assertIsNotNone(source, target)
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "BoatFluidPhysics.java"
                _materialize_text_file(
                    source, output, logical_relative=relative,
                    minecraft=properties[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform,
                    preprocess=True, native_source=False,
                )
                code = output.read_text(encoding="utf-8")
                with self.subTest(target=target):
                    if target == "1.19.2-forge":
                        self.assertIn("import net.minecraft.core.Registry;", code)
                        self.assertIn("Registry.FLUID.getKey(fluid)", code)
                        self.assertNotIn("BuiltInRegistries", code)
                    else:
                        self.assertIn("import net.minecraft.core.registries.BuiltInRegistries;", code)
                        self.assertIn("BuiltInRegistries.FLUID.getKey(fluid)", code)
                        self.assertNotIn("import net.minecraft.core.Registry;", code)
                    # Mojang mappings renamed the Entity ground accessor after 1.19.2.
                    # The helper must compile on every target, even though additional
                    # buoyancy is enabled only by Fabric at runtime.
                    self.assertIn("!boatOnGround(boat)", code)
                    if target == "1.19.2-forge":
                        self.assertIn("return boat.isOnGround();", code)
                        self.assertNotIn("return boat.onGround();", code)
                    else:
                        self.assertIn("return boat.onGround();", code)
                        self.assertNotIn("return boat.isOnGround();", code)
                    self.assertNotIn("//? if", code)
                    self.assertNotIn("/*?", code)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java toolchain required")
    def test_ground_accessor_compiles_against_versioned_entity_api(self):
        """Compile the actual preprocessed helper against the matching Entity method."""
        properties = load_properties()
        relative = "src/main/java/buildcraft/energy/fluid/BoatFluidPhysics.java"
        for target, accessor in (("1.19.2-forge", "isOnGround"),
                                 ("1.20.1-forge", "onGround"),
                                 ("26.3-fabric", "onGround")):
            layout = target_layout(target, properties)
            source = resolve_effective_source(layout, properties, relative)
            self.assertIsNotNone(source, target)
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                materialized = root / "BoatFluidPhysics.java"
                _materialize_text_file(
                    source, materialized, logical_relative=relative,
                    minecraft=properties[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform,
                    preprocess=True, native_source=False,
                )
                code = materialized.read_text(encoding="utf-8")
                match = re.search(r"private static boolean boatOnGround\(Entity boat\)\s*\{[^{}]*\}", code)
                self.assertIsNotNone(match, target)
                fixture = root / "BoatGroundAccessorContract.java"
                fixture.write_text(
                    "public class BoatGroundAccessorContract {\n"
                    f"  static class Entity {{ boolean {accessor}() {{ return true; }} }}\n"
                    f"  {match.group()}\n"
                    "  public static void main(String[] args) {\n"
                    "    if (!boatOnGround(new Entity())) throw new AssertionError();\n"
                    "  }\n}\n", encoding="utf-8",
                )
                compiled = subprocess.run(
                    ["javac", "--release", "17", "-d", str(root), str(fixture)],
                    capture_output=True, text=True, timeout=30,
                )
                self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
                executed = subprocess.run(
                    ["java", "-cp", str(root), "BoatGroundAccessorContract"],
                    capture_output=True, text=True, timeout=15,
                )
                self.assertEqual(0, executed.returncode, executed.stdout + executed.stderr)

    def test_material_data_agrees_with_registered_fluid_tables(self):
        model = MODEL.read_text(encoding="utf-8")
        pairs = re.findall(r'"([a-z_]+)"\s*,\s*new int\[\]\s*\{(\d+),\s*(\d+),\s*(\d+)\}', model)
        self.assertEqual(10, len(pairs))
        names = dict((name, (int(density), int(viscosity), int(boil)))
                     for name, density, viscosity, boil in pairs)
        self.assertEqual(10, len(names))
        for path in [*OLD_FORGE, NEOFORGE, FABRIC]:
            content = path.read_text(encoding="utf-8")
            with self.subTest(path=path):
                # All loaders register the same physical values and ordering.
                self.assertIn('"fuel_gaseous"', content)
                self.assertIn('"oil_residue"', content)
                for density, viscosity, boil in names.values():
                    self.assertRegex(content, rf'\{{\s*{density},\s*{viscosity},\s*{boil},')

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java toolchain required")
    def test_model_java17_real_physics_values(self):
        code = r'''
import buildcraft.energy.fluid.BoatFluidMaterial;
public class BoatFluidMaterialContract {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static BoatFluidMaterial at(String id) {
        BoatFluidMaterial v = BoatFluidMaterial.fromId("buildcraftenergy", id);
        check(v != null, "missing: " + id);
        return v;
    }
    public static void main(String[] args) {
        check(BoatFluidMaterial.fromId("minecraft", "water") == null, "vanilla water altered");
        check(BoatFluidMaterial.fromId("buildcraftenergy", "no_such_fluid") == null, "unknown fluid");
        check(at("oil").density() == 900 && at("oil").viscosity() == 2000, "oil properties");
        check(at("oil_flowing").equals(at("oil")), "flowing oil must match source");
        check(at("spout_oil_flowing").equals(at("oil")), "spout oil parity");
        check(at("oil_heat_1").viscosity() == 1500, "hot oil thinning");
        check(at("oil_heat_2").viscosity() == 1000, "searing oil thinning");
        check(at("oil_heat_1").temperature() == 350, "registered hot tier");
        check(at("oil_heat_2").temperature() == 400, "registered searing tier");
        check(at("oil_residue").viscosity() == 4000, "residue viscosity");
        check(at("fuel_light").horizontalDrag() >= 0.98, "liquid fuel water-like drag");
        check(at("oil").horizontalDrag() < 0.7, "crude oil needs high drag");
        check(at("oil_residue").horizontalDrag() < at("oil").horizontalDrag(), "residue must be slower");
        check(at("oil_heat_1").horizontalDrag() > at("oil").horizontalDrag(), "heated oil thins out");
        check(at("fuel_gaseous").isGas(), "cool gaseous fuel must be gas");
        check(at("fuel_gaseous_heat_2_flowing").isGas(), "searing gaseous fuel must be gas");
        check(at("fuel_light_heat_1").isGas(), "heated light fuel boils");
        check(at("fuel_mixed_light_heat_1").isGas(), "heated mixed light fuel boils");
        check(at("oil_distilled_heat_2").isGas(), "searing distilled oil boils");
        check(at("fuel_dense_heat_2").isGas(), "searing dense fuel boils");
        check(!at("oil_heat_2").isGas(), "crude oil stays liquid");
        check(!at("fuel_dense_heat_1").isGas(), "hot dense fuel stays liquid");
        check(at("fuel_gaseous").floatStrength() == 0.0, "no gas buoyancy");
        check(at("oil").floatStrength() > 0.0, "oil supports buoyancy");
        System.out.println("Boat fluid material Java contract OK");
    }
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            program = root / "BoatFluidMaterialContract.java"
            program.write_text(code, encoding="utf-8")
            subprocess.run(["javac", "--release", "17", "-d", str(root), str(MODEL), str(program)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            result = subprocess.run(["java", "-cp", str(root), "BoatFluidMaterialContract"],
                                    check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertIn("OK", result.stdout)


TEXTURES = ROOT / "source-shared/src/main/resources/assets/buildcraftenergy/textures/blocks/fluids"
NEO_CLIENT = "src/main/java/buildcraft/energy/BCEnergyClientProxy.java"
TEMPERATURES = ("cool", "hot", "searing")


class BoatFluidRenderMaskTests(unittest.TestCase):
    def test_liquid_sprites_use_translucent_pass_gas_remains_unmodified(self):
        # Minecraft 26.x classifies fluid render passes from the sprite's alpha.
        pairs = re.findall(r'"([a-z_]+)"\s*,\s*new int\[\]\s*\{\d+,\s*\d+,\s*(\d+)\}',
                           MODEL.read_text(encoding="utf-8"))
        self.assertEqual(10, len(pairs))
        liquid_count = 0
        for family, gas_threshold in pairs:
            for tier, name in enumerate(TEMPERATURES):
                for suffix in ("still", "flow"):
                    path = TEXTURES / family / f"{name}_{suffix}.png"
                    with self.subTest(sprite=path.relative_to(TEXTURES)):
                        with Image.open(path) as sprite:
                            self.assertEqual("RGBA", sprite.mode)
                            alphas = sprite.getchannel("A").getextrema()
                        if tier < int(gas_threshold):
                            # A one-step alpha change is visually indistinguishable
                            # from opaque but makes FluidModel choose TRANSLUCENT.
                            self.assertEqual((254, 254), alphas)
                            liquid_count += 1
                        else:
                            # Gaseous states do not have a liquid surface or buoyancy.
                            self.assertEqual((255, 255), alphas)
        self.assertEqual(40, liquid_count)

    def test_old_forge_registers_only_liquids_and_spout_in_water_pass(self):
        for version in ("1.19.2", "1.20.1"):
            source = ROOT / f"version-src/{version}-forge/src/main/java/buildcraft/energy/BCEnergyClientProxy.java"
            code = source.read_text(encoding="utf-8")
            with self.subTest(version=version):
                self.assertIn("event.enqueueWork(() -> {", code)
                self.assertIn("registerLiquidRenderLayers();", code)
                self.assertIn("getDensity() <= 0", code)
                self.assertIn("source.getFlowing(), RenderType.translucent()", code)
                self.assertIn("SPOUT_OIL_FLOWING.get(), RenderType.translucent()", code)
                self.assertNotIn("setRenderLayer(BCEnergyFluids.OIL_BLOCK", code)

    def test_neoforge_preprocessed_versions_use_correct_render_api(self):
        properties = load_properties()
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, properties)
            minecraft = properties[f"target.{target}.deps.minecraft"]
            source = resolve_effective_source(layout, properties, NEO_CLIENT)
            self.assertIsNotNone(source)
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "BCEnergyClientProxy.java"
                _materialize_text_file(source, output, logical_relative=NEO_CLIENT,
                                       minecraft=minecraft, family=layout.family,
                                       platform=layout.platform, preprocess=True,
                                       native_source=False)
                code = output.read_text(encoding="utf-8")
                with self.subTest(target=target):
                    if target == "1.21.1-neoforge":
                        self.assertIn("RenderType.translucent()", code)
                        self.assertIn("registerLiquidRenderLayers", code)
                        self.assertNotIn("ChunkSectionLayer.TRANSLUCENT", code)
                    elif target == "1.21.11-neoforge":
                        self.assertIn("ChunkSectionLayer.TRANSLUCENT", code)
                        self.assertIn("registerLiquidRenderLayers", code)
                        self.assertNotIn("RenderType.translucent()", code)
                    else:
                        self.assertNotIn("ItemBlockRenderTypes", code)
                        self.assertNotIn("registerLiquidRenderLayers", code)


if __name__ == "__main__":
    unittest.main()
