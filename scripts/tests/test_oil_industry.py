"""Exercise the shared mode policy independently of Minecraft/loader classes."""
from pathlib import Path
import subprocess
import json
import itertools
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file
from source_lookup import read_materialized_java

class OilIndustryTest(unittest.TestCase):
    def test_registered_bucket_capabilities_across_targets(self):
        props = load_properties()
        for target in props["targets"].split(","):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                layout = target_layout(target, props)
                minecraft = props[f"target.{target}.deps.minecraft"]
                def effective(relative):
                    source = resolve_effective_source(layout, props, relative)
                    self.assertIsNotNone(source, relative)
                    out = Path(tmp) / relative
                    downports = tuple(p for p in (layout.family_downport_root, layout.family_platform_downport_root) if p)
                    _materialize_text_file(source, out, logical_relative=relative,
                        minecraft=minecraft, family=layout.family, platform=layout.platform,
                        preprocess=True, native_source=any(source.is_relative_to(p) for p in downports),
                        native_passthrough=layout.platform == "fabric")
                    return out.read_text(encoding="utf-8")
                fluids = effective("src/main/java/buildcraft/energy/BCEnergyFluids.java")
                self.assertIn("OilIndustry.classicBucketName(name, heat)", fluids)
                if layout.platform == "neoforge":
                    energy = effective("src/main/java/buildcraft/energy/BCEnergy.java")
                    self.assertIn("modEventBus.addListener(this::registerCapabilities)", energy)
                    self.assertIn("for (var bucket : BCEnergyFluids.OIL_BUCKET)", energy)
                    if minecraft == "1.21.1":
                        self.assertIn("event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new FluidBucketWrapper(stack), bucket.get())", energy)
                    else:
                        self.assertIn("event.registerItem(Capabilities.Fluid.ITEM, (stack, context) -> new BucketResourceHandler(context), bucket.get())", energy)
                elif layout.platform == "forge":
                    self.assertIn("initCapabilities(", fluids)
                    self.assertIn("new net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper(stack)", fluids)
                test = effective("src/gametest/java/buildcraft/gametest/FluidCompatibilityGameTests.java")
                self.assertIn("petroleumBucketsExposeNativeFluidHandlers", test)
                self.assertIn("distillerAcceptsOilFromRegisteredBucket", test)
                self.assertIn("FluidUtil.getFluidHandler(new ItemStack(bucket))", test)

    def test_jade_and_native_fluid_names_across_targets(self):
        props = load_properties()
        for target in props["targets"].split(","):
            with self.subTest(target=target):
                jade = read_materialized_java(target, "src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java")
                self.assertIn("OilIndustry.classicFluidName(", jade)
                self.assertIn("return classicName == null ? block.getName() : Component.translatable(classicName);", jade)
                if target.endswith("-fabric"):
                    fluids = read_materialized_java(target, "src/main/java/buildcraft/energy/BCEnergyFluids.java")
                    for entry in ['crudeOil[0], "oil"', 'fuelDense[0], "fuel_dense"', 'SPOUT_OIL_SOURCE.get(), "spout_oil"']:
                        self.assertIn("registerPetroleumName(" + entry + ");", fluids)
                    self.assertIn("FluidVariantAttributes.register(source, new FluidVariantAttributeHandler()", fluids)
                    self.assertIn("FluidVariantAttributeHandler.super.getName(variant)", fluids)
                else:
                    fluid_type = read_materialized_java(target, "src/main/java/buildcraft/energy/fluid/BCFluidType.java")
                    self.assertIn("OilIndustry.fluidDescriptionId(super.getDescriptionId())", fluid_type)
        lang = json.loads((ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json").read_text(encoding="utf-8"))
        self.assertEqual("Oil", lang["fluid_type.buildcraftenergy.classic.oil"])
        self.assertEqual("Fuel", lang["fluid_type.buildcraftenergy.classic.fuel"])
        self.assertIn("Cool", lang["fluid_type.buildcraftenergy.oil"])
        self.assertIn("Dense Fuel", lang["fluid_type.buildcraftenergy.fuel_dense"])

    def test_classic_input_recovery_all_targets(self):
        props = load_properties()
        for target in props["targets"].split(","):
            with self.subTest(target=target):
                def code(name):
                    return read_materialized_java(target, "src/main/java/buildcraft/" + name + ".java")
                runtime = code("lib/fluid/FluidDropRuntime")
                recovery = runtime[runtime.index("public static void ejectInvalidClassicInput("):]
                self.assertIn("!(level instanceof ServerLevel) || !OilIndustry.classic()", recovery)
                self.assertIn("!OilIndustry.isPetroleum(id.getNamespace(), id.getPath()) || input.isFluidValid(fluid)", recovery)
                self.assertLess(recovery.index(".addFluidDrops(drops, fluid)"), recovery.index("input.drainInternal(fluid.getAmount(), FluidAction.EXECUTE)"))
                self.assertLess(recovery.index("input.drainInternal"), recovery.index("InventoryUtil.dropAll"))
                for name, tanks in (("factory/tile/TileDistiller_BC8", ["tankIn"]),
                                    ("factory/tile/TileHeatExchange", ["tankInput"]),
                                    ("energy/tile/TileEngineIron_BC8", ["tankFuel", "tankCoolant"])):
                    calls = [line.strip() for line in code(name).splitlines() if "FluidDropRuntime.ejectInvalidClassicInput(" in line]
                    self.assertEqual(len(calls), len(tanks))
                    for line, tank in zip(calls, tanks):
                        self.assertTrue(line.endswith(", " + tank + ");"), line)
                recipes = code("energy/BCEnergyRecipes")
                for heat, name in enumerate(("oil", "oil_heat_1", "oil_heat_2")):
                    self.assertIn(f'registerClassicFuel("{name}", BCEnergyFluids.crudeOil[{heat}], 3, 10_000);', recipes)
                energy = code("energy/BCEnergy")
                self.assertIn("if (!OilIndustry.classic()", energy)
                self.assertIn('progress.getCriterion("classic_oil_seen")', energy)
                self.assertIn('progress.getCriterion("fuel_dense_cool_bucket")', energy)
                self.assertIn("AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_REFINE)", energy)
                self.assertLess(energy.index("checkClassicRefinement(player);"), energy.index("if (hasAdvancement(player, ADVANCEMENT_FIND_OIL_SPOT))"))

    def test_classic_advancement_keeps_modern_progress(self):
        for family, directory in (("old", "advancements"), ("1.21.X", "advancement")):
            path = ROOT / "source-families" / family / "src/main/resources/data/buildcraftenergy" / directory / "refine_and_redefine.json"
            data = json.loads(path.read_text())
            criteria = data["criteria"]
            requirements = data["requirements"]
            self.assertEqual(set(criteria), {criterion for group in requirements for criterion in group})
            self.assertEqual(criteria["code_trigger"]["trigger"], "minecraft:impossible")
            self.assertEqual(criteria["classic_oil_seen"]["conditions"]["items"][0]["items"],
                [f"buildcraftenergy:oil/{heat}_bucket" for heat in ("cool", "hot", "searing")])
            original = set(criteria) - {"classic_oil_seen", "code_trigger", "has_all_oil_products"}
            self.assertEqual(len(original), 10)
            # Exhaustively verify the new optional tracking criterion does not relax modern requirements.
            names = sorted(criteria)
            for values in itertools.product((False, True), repeat=len(names)):
                done = {name for name, value in zip(names, values) if value}
                actual = all(done.intersection(group) for group in requirements)
                expected = "code_trigger" in done or "has_all_oil_products" in done or original.issubset(done)
                self.assertEqual(actual, expected, done)

    def test_mode_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            main = base / 'Exercise.java'
            main.write_text('''
import buildcraft.energy.OilIndustry;
public class Exercise {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        var modern = OilIndustry.Mode.MODERN;
        var classic = OilIndustry.Mode.CLASSIC;
        check(!OilIndustry.classic());
        check(OilIndustry.classicFluidName("buildcraftenergy", "oil") == null);
        check(OilIndustry.fluidDescriptionId("fluid_type.buildcraftenergy.fuel_dense").equals("fluid_type.buildcraftenergy.fuel_dense"));
        for (String oil : new String[]{"oil", "oil_heat_1", "oil_heat_2"}) {
            check(!OilIndustry.recipeEnabled(modern, "buildcraftfactory", "distiller/classic_" + oil));
            check(OilIndustry.recipeEnabled(classic, "buildcraftfactory", "distiller/classic_" + oil));
            check(OilIndustry.recipeEnabled(modern, "buildcraftfactory", "distiller/buildcraftenergy/" + oil));
            check(!OilIndustry.recipeEnabled(classic, "buildcraftfactory", "distiller/buildcraftenergy/" + oil));
            check(!OilIndustry.recipesCanOverlap("buildcraftfactory", "distiller/classic_" + oil,
                "buildcraftfactory", "distiller/buildcraftenergy/" + oil));
            check(OilIndustry.fuelEnabled(classic, "buildcraftenergy", "fuel/classic/" + oil));
            check(!OilIndustry.fuelEnabled(modern, "buildcraftenergy", "fuel/classic/" + oil));
            check(OilIndustry.isPetroleum("buildcraftenergy", oil));
        }
        check(OilIndustry.isPetroleum("buildcraftenergy", "oil_residue_heat_2_flowing"));
        check(OilIndustry.isPetroleum("buildcraftenergy", "spout_oil_flowing"));
        check(!OilIndustry.isPetroleum("addon", "oil_heat_2"));
        check(!OilIndustry.isPetroleum("minecraft", "water"));
        check(!OilIndustry.isPetroleum("buildcraftenergy", "oil_heat_42"));
        check(!OilIndustry.recipeEnabled("buildcraftfactory", "distiller/classic_oil"));
        check(OilIndustry.recipeEnabled("buildcraftfactory", "distiller/buildcraftenergy/oil"));
        check(!OilIndustry.recipesCanOverlap("buildcraftfactory", "distiller/classic_oil",
            "buildcraftfactory", "distiller/buildcraftenergy/oil"));
        check(OilIndustry.recipesCanOverlap("buildcraftfactory", "distiller/classic_oil",
            "addon", "distiller/custom_oil"));
        check(OilIndustry.recipesCanOverlap("buildcraftfactory", "distiller/buildcraftenergy/oil",
            "addon", "distiller/custom_oil"));
        check(OilIndustry.recipesCanOverlap("buildcraftfactory", "distiller/buildcraftenergy/oil",
            "buildcraftfactory", "distiller/buildcraftenergy/oil_distilled"));
        OilIndustry.setRemoteMode(classic);
        check(OilIndustry.classic());
        for (String oil : new String[]{"oil", "oil_flowing", "spout_oil", "spout_oil_flowing"}) {
            check(OilIndustry.classicFluidName("buildcraftenergy", oil).equals("fluid_type.buildcraftenergy.classic.oil"));
        }
        for (String fuel : new String[]{"fuel_dense", "fuel_dense_flowing"}) {
            check(OilIndustry.classicFluidName("buildcraftenergy", fuel).equals("fluid_type.buildcraftenergy.classic.fuel"));
        }
        check(OilIndustry.fluidDescriptionId("fluid_type.buildcraftenergy.oil").equals("fluid_type.buildcraftenergy.classic.oil"));
        check(OilIndustry.fluidDescriptionId("fluid_type.buildcraftenergy.fuel_dense").equals("fluid_type.buildcraftenergy.classic.fuel"));
        for (String other : new String[]{"oil_heat_1", "oil_heat_2_flowing", "fuel_dense_heat_1", "fuel_light", "oil_residue"}) {
            check(OilIndustry.classicFluidName("buildcraftenergy", other) == null);
            String key = "fluid_type.buildcraftenergy." + other;
            check(OilIndustry.fluidDescriptionId(key).equals(key));
        }
        check(OilIndustry.classicFluidName("addon", "oil") == null);
        check(OilIndustry.fluidDescriptionId("fluid_type.addon.fuel_dense").equals("fluid_type.addon.fuel_dense"));
        check(OilIndustry.recipeEnabled("buildcraftfactory", "distiller/classic_oil"));
        check(!OilIndustry.recipeEnabled("buildcraftfactory", "distiller/buildcraftenergy/oil"));
        check(!OilIndustry.recipeEnabled("buildcraftfactory", "heat_exchanger/cooling/buildcraftenergy/oil"));
        check(OilIndustry.recipeEnabled("buildcraftfactory", "heat_exchanger/cooling/minecraft/lava"));
        check(OilIndustry.recipeEnabled("addon", "distiller/buildcraftenergy/oil"));
        check(!OilIndustry.fuelEnabled("buildcraftenergy", "fuel/buildcraftenergy/oil"));
        check(OilIndustry.fuelEnabled("buildcraftenergy", "fuel/classic/oil"));
        check(OilIndustry.bucketEnabled("buildcraftenergy", "oil/cool_bucket"));
        check(OilIndustry.bucketEnabled("buildcraftenergy", "fuel_dense/cool_bucket"));
        check(!OilIndustry.bucketEnabled("buildcraftenergy", "oil/hot_bucket"));
        check(!OilIndustry.fluidEnabled("buildcraftenergy", "oil_heat_1_flowing"));
        check(OilIndustry.fluidEnabled("buildcraftenergy", "fuel_dense"));
        check(OilIndustry.fluidEnabled("buildcraftenergy", "spout_oil_flowing"));
        check(OilIndustry.fluidEnabled("addon", "oil_heat_1"));
        OilIndustry.setServerRunning(true);
        check(!OilIndustry.classic());
        OilIndustry.setServerMode(classic);
        check(OilIndustry.classic());
        OilIndustry.setRemoteMode(null);
        OilIndustry.setServerMode(modern);
        OilIndustry.setServerRunning(false);
        check(!OilIndustry.classic());
        check(OilIndustry.fluidDescriptionId("fluid_type.buildcraftenergy.oil").equals("fluid_type.buildcraftenergy.oil"));
    }
}
''', encoding='utf-8')
            source = ROOT / 'source-shared/src/main/java/buildcraft/energy/OilIndustry.java'
            subprocess.run(['javac', '--release', '17', '-d', tmp, str(source), str(main)], check=True, timeout=30)
            subprocess.run(['java', '-cp', tmp, 'Exercise'], check=True, timeout=10)

if __name__ == '__main__':
    unittest.main()
