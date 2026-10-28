"""Machine recipe contracts: source data, serialization, JEI, identity and reload.
The suite retains the original behavioral assertions across all supported targets.
Do not replace selected-source checks with repository-wide discovery.
"""
from __future__ import annotations

import json
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path, PurePath, PurePosixPath, PureWindowsPath
from recipe_test_targets import jei_plugin_sources, message_manager_sources

ROOT = Path(__file__).resolve().parents[2]

ENERGY = {
    "picker": 800, "carrier": 800, "fluid_carrier": 800,
    "lumberjack": 3200, "harvester": 3200, "miner": 3200,
    "planter": 3200, "farmer": 3200, "leave_cutter": 3200,
    "butcher": 3200, "shovelman": 3200, "pump": 3200,
    "delivery": 12800, "knight": 12800, "bomber": 12800,
    "stripes": 12800, "builder": 51200,
}

def uses_direct_ingredient_items(path: PurePath) -> bool:
    """Select the MC 1.19-1.21.1 Ingredient API without OS-specific separators."""
    parts = path.parts
    return "version-src" in parts or (
        "source-downports" in parts and "1.21.X" in parts and "1.21.1" in parts
    )

class RecipeContractsTest(unittest.TestCase):
    # Built-in machine recipe definitions
    def test_built_in_distillation_and_heat_exchange(self):
        for family, directory in (("old", "recipes"), ("1.21.X", "recipe"), ("26.X", "recipe")):
            with self.subTest(family=family):
                base = ROOT / "source-families" / family / "src/main/resources/data/buildcraftfactory" / directory
                distiller = sorted((base / "distiller").rglob("*.json"))
                heat = sorted((base / "heat_exchanger").rglob("*.json"))
                self.assertEqual(len(distiller), 10)
                self.assertEqual(len(heat), 42)
                manifest = json.loads((base.parent / "bundled_machine_recipes.json").read_text(encoding="utf-8"))
                self.assertEqual(len(manifest), 52)
                self.assertEqual(set(manifest), {str(f.relative_to(base.parent)).replace("\\", "/") for f in [*distiller, *heat]})
                fluid_inputs = set()
                for path in distiller:
                    data = json.loads(path.read_text(encoding="utf-8"))
                    self.assertEqual(data["type"], "buildcraftfactory:distillation")
                    self.assertGreater(data["input"]["amount_mb"], 0)
                    self.assertGreater(data["energy"]["mj"], 0)
                    self.assertTrue(data["outputs"])
                    self.assertGreater(sum(v["amount_mb"] for v in data["outputs"].values()), 0)
                    for obj in (data["input"], *data["outputs"].values()):
                        self.assertRegex(obj["fluid"], r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
                    self.assertNotIn(data["input"]["fluid"], fluid_inputs)
                    fluid_inputs.add(data["input"]["fluid"])
                for path in heat:
                    data = json.loads(path.read_text(encoding="utf-8"))
                    self.assertEqual(data["type"], "buildcraftfactory:heat_exchange")
                    self.assertGreater(data["input"]["amount_mb"], 0)
                    if data["mode"] == "heating":
                        self.assertGreater(data["heat_to"], data["heat_from"])
                    elif data["mode"] == "cooling":
                        self.assertLess(data["heat_to"], data["heat_from"])
                    else:
                        self.fail("Invalid heat mode: " + str(path))
                    if "output" in data:
                        self.assertGreater(data["output"]["amount_mb"], 0)

    def test_multitarget_loader_and_native_serializers(self):
        for target in (
            "version-src/1.19.2-forge", "version-src/1.20.1-forge",
            "source-downports/1.21.X/1.21.1/neoforge",
            "source-family-platforms/1.21.X/neoforge",
            "source-downports/26.X/26.1.2/neoforge", "source-families/26.X",
        ):
            file = ROOT / target / "src/main/java/buildcraft/lib/BCLibEventDist.java"
            with self.subTest(file=file):
                self.assertIn("reloadMachineRecipes", file.read_text(encoding="utf-8"))
                self.assertIn("MachineRecipeDatapackReloadListener", file.read_text(encoding="utf-8"))
        for folder in ("version-src/1.19.2-forge", "version-src/1.20.1-forge",
                       "source-families/1.21.X", "source-families/26.X"):
            file = ROOT / folder / "src/main/java/buildcraft/factory/BCFactoryRecipeSerializers.java"
            self.assertIn("DISTILLATION", file.read_text(encoding="utf-8"))
            self.assertIn("HEAT_EXCHANGE", file.read_text(encoding="utf-8"))

    def test_deny_wins_implementation(self):
        loader = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text(encoding="utf-8")
        policy = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/RecipeBlacklist.java").read_text(encoding="utf-8")
        self.assertIn("listResourceStacks", loader)
        self.assertIn("for (Object resource : entry.getValue())", loader)
        self.assertIn("mergeFailClosed", loader)
        self.assertIn("mergeFailClosed", policy)
        self.assertIn("new Snapshot(current.generation() + 1", policy)
        self.assertIn("Duplicate recipe ID", loader)
        self.assertIn("Duplicate recipe content", loader)
        self.assertIn("Overlapping machine recipe input", loader)

    def test_policy_real_java(self):
        """Compile and run the actual immutable blacklist holder with tiny MC stubs."""
        policy = ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/RecipeBlacklist.java"
        with tempfile.TemporaryDirectory(prefix="bcce-recipe-policy-") as td:
            base = Path(td)
            stubs = {
                "net/minecraft/resources/ResourceLocation.java": '''
                    package net.minecraft.resources;
                    public record ResourceLocation(String id) {
                        public static ResourceLocation tryParse(String s) { return new ResourceLocation(s); }
                    }
                ''',
                "buildcraft/api/v2/recipe/RecipeDefinition.java": '''
                    package buildcraft.api.v2.recipe;
                    public interface RecipeDefinition {
                        enum Kind {INTEGRATION, DISTILLATION, HEATING, COOLING, ASSEMBLY, PROGRAMMING}
                    }
                ''',
                "buildcraft/lib/recipe/BlacklistExercise.java": '''
                    package buildcraft.lib.recipe;
                    import java.util.*;
                    import net.minecraft.resources.ResourceLocation;
                    public class BlacklistExercise {
                      public static void main(String[] args) {
                        var oil = ResourceLocation.tryParse("bc:distiller/oil");
                        var steam = ResourceLocation.tryParse("other:steam");
                        RecipeBlacklist.publish(Map.of("distiller", Set.of(oil)));
                        if (!RecipeBlacklist.isDenied("distiller", oil)) throw new AssertionError("ban missing");
                        if (RecipeBlacklist.isDenied("assembly_table", oil)) throw new AssertionError("wrong machine");
                        RecipeBlacklist.mergeFailClosed(Map.of("distiller", Set.of(steam)));
                        if (!RecipeBlacklist.isDenied("distiller", oil) || !RecipeBlacklist.isDenied("distiller", steam))
                            throw new AssertionError("merging must not lift old ban");
                        RecipeBlacklist.publish(Map.of());
                        if (RecipeBlacklist.isDenied("distiller", oil)) throw new AssertionError("successful reload must lift removed bans");
                        System.out.println("deny wins PASS");
                      }
                    }
                ''',
            }
            inputs = [policy]
            for name, body in stubs.items():
                dest = base / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(textwrap.dedent(body), encoding="utf-8")
                inputs.append(dest)
            classes = base / "classes"
            compiled = subprocess.run(["javac", "--release", "17", "-d", str(classes),
                                       *map(str, inputs)], capture_output=True, text=True, timeout=20)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(["java", "-cp", str(classes), "buildcraft.lib.recipe.BlacklistExercise"],
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("deny wins PASS", run.stdout)

    # Programming and integration recipes
    def test_robot_programming_and_integration_json_all_families(self):
        for family, folder in (("old", "recipes"), ("1.21.X", "recipe"), ("26.X", "recipe")):
            base = ROOT / f"source-families/{family}/src/main/resources/data/buildcraftrobotics/{folder}"
            scripts = sorted((base / "programming_table").glob("*.json"))
            with self.subTest(family=family):
                self.assertEqual(len(scripts), len(ENERGY))
                self.assertEqual({p.stem for p in scripts}, set(ENERGY))
                for item in scripts:
                    data = json.loads(item.read_text(encoding="utf-8"))
                    self.assertEqual(data["type"], "buildcraftsilicon:programming")
                    self.assertEqual(data["board_key"], item.stem)
                    self.assertEqual(data["energy"]["mj"], ENERGY[item.stem])
                dynamic = json.loads((base / "integration_table/robot_board_integration.json").read_text(encoding="utf-8"))
                self.assertEqual(dynamic["type"], "buildcraftsilicon:robot_board_integration")
                self.assertEqual(dynamic["energy"]["mj"], 5000)

    def test_no_hardcoded_builtin_registration(self):
        source = (ROOT / "source-shared/src/main/java/buildcraft/robotics/recipes/RobotIntegrationRecipe.java").read_text(encoding="utf-8")
        self.assertIn("MachineRecipeDatapackReloadListener.registerHandler", source)
        self.assertNotIn("service.register(ID", source)
        self.assertIn("powerMicroMj", source)
        self.assertIn("integration_table/robot_board_integration", source)

    def test_json_reader_and_serializers_all_targets(self):
        loader = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text(encoding="utf-8")
        self.assertIn('"integration_table", "programming_table"', loader)
        self.assertIn('"buildcraftsilicon:integration"', loader)
        self.assertIn('"buildcraftsilicon:programming"', loader)
        self.assertIn('HANDLERS.get(type)', loader)
        self.assertIn("Unregistered board key", loader)
        self.assertNotIn("Two programming recipes target board key", loader)
        self.assertIn("Duplicate programming recipe content at", loader)
        for folder in ("version-src/1.19.2-forge", "version-src/1.20.1-forge", "source-families/1.21.X", "source-families/26.X"):
            source = (ROOT / folder / "src/main/java/buildcraft/silicon/BCSiliconRecipes.java").read_text(encoding="utf-8")
            with self.subTest(folder=folder):
                self.assertIn('SERIALIZERS.register("programming"', source)
                self.assertIn('SERIALIZERS.register("integration"', source)
                self.assertIn('SERIALIZERS.register("robot_board_integration"', source)

    def test_machine_runtime_and_client_are_data_owned(self):
        for folder in ("source-families/1.21.X", "source-platforms/forge"):
            table = (ROOT / folder / "src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java").read_text(encoding="utf-8")
            integration = (ROOT / folder / "src/main/java/buildcraft/silicon/tile/TileIntegrationTable.java").read_text(encoding="utf-8")
            with self.subTest(folder=folder):
                self.assertIn("ProgrammingRecipeAccess.options(", table)
                self.assertIn("lastRecipeGeneration", table)
                self.assertIn("syncedProgrammingOptions", table)
                self.assertIn("buffer.writeLong(recipe.requiredMicroJoules())", table)
                self.assertIn("optionDefinitions.get(absolute).requiredMicroJoules()", table)
                self.assertIn("AssemblyIngredients.remaining(items, supplies)", integration)
                self.assertNotIn("selected.energyCost() *", table)
        client = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/ClientMachineRecipeCatalog.java").read_text(encoding="utf-8")
        self.assertIn("visibleDefinition", client)
        for file in jei_plugin_sources():
            source = file.read_text(encoding="utf-8")
            self.assertIn("ProgrammingRecipeAccess.options(true)", source)
            self.assertIn("integration_table/robot_board_integration", source)
            self.assertIn("for (BoardEntry board : BCRoboticsBoards.robotEntries())", source)
            self.assertNotIn('"buildcraftrobotics:robot_board_integration"', source)

    # JEI, serializers and machine persistence
    def test_ingredient_target_api_selection_cross_platform(self):
        # Regression: WindowsPath uses backslashes in str(path), while Linux
        # uses forward slashes. Both must select the same ingredient API.
        examples = (
            ("version-src/1.19.2-forge", True),
            ("version-src/1.20.1-forge", True),
            ("source-downports/1.21.X/1.21.1/neoforge", True),
            ("source-family-platforms/1.21.X/neoforge", False),
            ("source-downports/26.X/26.1.2/neoforge", False),
            ("source-downports/26.X/26.2/neoforge", False),
            ("source-family-platforms/26.X/neoforge", False),
        )
        for path_string, expected in examples:
            for flavour in (PurePosixPath, PureWindowsPath):
                with self.subTest(target=path_string, flavour=flavour.__name__):
                    self.assertEqual(uses_direct_ingredient_items(flavour(path_string)), expected)

    def test_static_integration_jei_on_all_seven_targets(self):
        files = jei_plugin_sources()
        self.assertEqual(len(files), 7)
        for p in files:
            with self.subTest(target=p):
                text = p.read_text(encoding="utf-8")
                self.assertEqual(text.count("collectIntegrationRecipes();"), 2)
                self.assertIn("ClientMachineRecipeCatalog.visibleRecipes(StaticIntegrationRecipe.class)", text)
                self.assertIn("integrationIngredientStacks(fixed.centerIngredient())", text)
                self.assertIn("integrationIngredientStacks(additional.get(index))", text)
                if uses_direct_ingredient_items(p):
                    self.assertIn("ingredient.ingredient().getItems()", text)
                else:
                    self.assertIn("IngredientCompat.getItems(ingredient.ingredient())", text)
                self.assertIn("builder.addSlot(RecipeIngredientRole.OUTPUT, 121, 27)", text)
                self.assertIn("fixed.requiredMicroJoules(fixed.result())", text)
                self.assertNotIn('isDenied("programming_table",\n', text)
        recipe = (ROOT / "source-shared/src/main/java/buildcraft/api/v2/recipe/StaticIntegrationRecipe.java").read_text(encoding="utf-8")
        self.assertIn("public ItemStack result() { return result.copy(); }", recipe)

    def test_board_permissions_use_actual_recipe_ids(self):
        for folder in ("source-platforms/forge", "source-families/1.21.X"):
            code = (ROOT / folder / "src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java").read_text(encoding="utf-8")
            with self.subTest(folder=folder):
                self.assertNotIn("boardRecipeId(BoardEntry", code)
                self.assertNotIn("isBoardBlocked(BoardEntry", code)
                self.assertIn("ProgrammingRecipeAccess.options(", code)
                self.assertIn("optionDefinitions.get(absolute).requiredMicroJoules()", code)
                self.assertIn("syncedProgrammingOptions = List.copyOf(recipes)", code)
                # Both source families call RecipeBlacklist.generation(); without
                # importing its shared package, old Forge compilation fails.
                self.assertIn("import buildcraft.lib.recipe.RecipeBlacklist;", code)
                self.assertEqual(code.count("RecipeBlacklist.generation()"), 1)
        access = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/ProgrammingRecipeAccess.java").read_text(encoding="utf-8")
        self.assertIn("MachineRecipeService", (ROOT / "source-shared/src/main/java/buildcraft/api/v2/recipe/MachineRecipeService.java").read_text(encoding="utf-8"))
        self.assertIn("recipes(ProgrammingRecipeDefinition.class)", access)

    def test_modern_machine_recipe_compile_compatibility(self):
        # The 1.21.11 and 26.X mappings use Identifier, not ResourceLocation.
        recipe_id = '"buildcraftrobotics:integration_table/robot_board_integration"'
        for family in ("1.21.X", "26.X"):
            path = (ROOT / f"source-family-platforms/{family}/neoforge/src/main/java/"
                    "buildcraft/compat/jei/BuildCraftJeiPlugin.java")
            with self.subTest(family=family):
                source = path.read_text(encoding="utf-8")
                self.assertIn('import net.minecraft.resources.Identifier;', source)
                self.assertGreaterEqual(source.count('Identifier.fromNamespaceAndPath('
                                               '"buildcraftrobotics", "integration_table/robot_board_integration")'), 1)
                self.assertIn('"buildcraftrobotics", "integration_table/robot_board_integration"', source)
                self.assertNotIn('net.minecraft.resources.ResourceLocation.tryParse(' + recipe_id + ')', source)

        # 26.1.2 and 26.2 each override the family JEI source. Even if
        # source-family-platforms/26.X is fixed, these downports can retain
        # obsolete ResourceLocation references and fail compilation.
        for version in ("26.1.2", "26.2"):
            path = (ROOT / f"source-downports/26.X/{version}/neoforge/src/main/java/"
                    "buildcraft/compat/jei/BuildCraftJeiPlugin.java")
            with self.subTest(target=version):
                source = path.read_text(encoding="utf-8")
                self.assertIn('import net.minecraft.resources.Identifier;', source)
                self.assertGreaterEqual(source.count('Identifier.fromNamespaceAndPath('
                                               '"buildcraftrobotics", "integration_table/robot_board_integration")'), 1)
                self.assertIn('"buildcraftrobotics", "integration_table/robot_board_integration"', source)
                self.assertNotIn('net.minecraft.resources.ResourceLocation.tryParse(' + recipe_id + ')', source)

        # 1.21.1 still uses ResourceLocation; leave its downport untouched.
        old_jei = (ROOT / "source-downports/1.21.X/1.21.1/neoforge/src/main/java/"
                   "buildcraft/compat/jei/BuildCraftJeiPlugin.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(old_jei.count('ResourceLocation.tryParse(' + recipe_id + ')'), 1)

        # In 1.21.11 CustomRecipe#getSerializer has a covariant return
        # RecipeSerializer<? extends CustomRecipe>, unlike RecipeSerializer<?>.
        for file in ("factory/BCFactoryRecipeSerializers.java", "silicon/BCSiliconRecipes.java"):
            path = ROOT / "source-families/1.21.X/src/main/java/buildcraft" / file
            with self.subTest(file=file):
                source = path.read_text(encoding="utf-8")
                self.assertIn('RecipeSerializer<? extends CustomRecipe> getSerializer()', source)
                self.assertNotIn('RecipeSerializer<?> getSerializer()', source)

    def test_distiller_identity_persisted_all_implementations(self):
        for folder in ("source-platforms/forge", "source-platforms/neoforge"):
            for name in ("TileDistiller.java", "TileDistiller_BC8.java"):
                p = ROOT / folder / "src/main/java/buildcraft/factory/tile" / name
                text = p.read_text(encoding="utf-8")
                with self.subTest(source=p):
                    self.assertIn("distillRecipeKey", text)
                    self.assertIn("DistillationProgressIdentity.fromSaved", text)
                    self.assertIn("MachineRecipeApiBridge.findDistillationMatch(tankIn.getFluid())", text)
                    self.assertIn("if (distillPower > 0 && !activeKey.equals(inFlightRecipeKey))", text)
                    self.assertIn("queueProgressRefund();", text)
                    self.assertIn("matching = null", text)
                    if name.endswith("_BC8.java"):
                        self.assertNotIn("if (pendingPowerRefund > 0)", text)
                    self.assertIn("refundPendingPower();", text)
        for folder in ("source-platforms/forge", "source-platforms/neoforge", "source-families/26.X"):
            text = (ROOT / folder / "src/main/java/buildcraft/lib/recipe/MachineRecipeApiBridge.java").read_text(encoding="utf-8")
            self.assertIn("RecipeMatch<DistillationRecipeDefinition> findDistillationMatch", text)

    def test_distiller_nbt_uses_cross_version_persistence_facade(self):
        # 1.21.1 deliberately excludes NbtCompat; 1.21.11 and 26.X include it.
        # Reading via BCValueInput works on all of these targets.
        reader = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft/persistence/BCValueInput.java").read_text(encoding="utf-8")
        accessor = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft/persistence/TagAccess.java").read_text(encoding="utf-8")
        compat = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/compat/NbtCompat.java").read_text(encoding="utf-8")
        self.assertIn("public String readString(String key) { return TagAccess.getString(tag, key); }", reader)
        self.assertIn("static String getString(CompoundTag tag, String key)", accessor)
        self.assertIn("//? source if >=1.21.11", compat)
        for name in ("TileDistiller.java", "TileDistiller_BC8.java"):
            file = ROOT / "source-platforms/neoforge/src/main/java/buildcraft/factory/tile" / name
            source = file.read_text(encoding="utf-8")
            with self.subTest(file=file):
                self.assertIn('nbt.putString("distillRecipeKey", inFlightRecipeKey)', source)
                self.assertIn('bcData.readString("distillRecipeKey")', source)
                self.assertNotIn('NbtCompat.getString(nbt, "distillRecipeKey")', source)

    def test_only_active_minecraft_recipe_folder_is_loaded(self):
        loader = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text(encoding="utf-8")
        self.assertIn('List.of("blacklist_recipes", "recipes")', loader)
        self.assertIn('List.of("blacklist_recipes", "recipe")', loader)
        self.assertIn("//? if <1.21 {", loader)
        import sys
        sys.path.insert(0, str(ROOT / "scripts"))
        from source_preprocessor import preprocess_text
        for mc, family, platform, recipe_folder in (
            ("1.19.2", "old", "forge", "recipes"),
            ("1.20.1", "old", "forge", "recipes"),
            ("1.21.1", "1.21.X", "neoforge", "recipe"),
            ("1.21.11", "1.21.X", "neoforge", "recipe"),
            ("26.3", "26.X", "neoforge", "recipe"),
        ):
            with self.subTest(target=mc):
                expanded = preprocess_text(loader, minecraft=mc, family=family, platform=platform)
                selected = [line for line in expanded.splitlines() if 'for (String prefix :' in line]
                self.assertEqual(selected, [f'            for (String prefix : List.of("blacklist_recipes", "{recipe_folder}")) {{'])

    def test_real_java_fingerprint_stability(self):
        """Exercise the actual progress-identity Java helper with typed compile stubs."""
        src = ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/DistillationProgressIdentity.java"
        cases = {
            "buildcraft/api/v2/fluid/FluidVariant.java": '''
                package buildcraft.api.v2.fluid;
                public record FluidVariant(long code) {
                    public long stableHash64() {return code;}
                }
            ''',
            "buildcraft/api/v2/fluid/FluidAmount.java": '''
                package buildcraft.api.v2.fluid;
                public record FluidAmount(long milliBuckets) {}
            ''',
            "buildcraft/api/v2/fluid/FluidVolume.java": '''
                package buildcraft.api.v2.fluid;
                public record FluidVolume(FluidVariant variant, FluidAmount amount) {
                    public boolean isEmpty() {return variant==null;}
                    public FluidVariant requireVariant() {return variant;}
                }
            ''',
            "buildcraft/api/v2/recipe/FluidIngredient.java": '''
                package buildcraft.api.v2.recipe;
                import java.util.Optional;
                import buildcraft.api.v2.fluid.*;
                public record FluidIngredient(FluidVariant variant, FluidAmount amount) {
                    public Optional<FluidVariant> representativeVariant() {return Optional.ofNullable(variant);}
                }
            ''',
            "buildcraft/api/v2/recipe/DistillationRecipeDefinition.java": '''
                package buildcraft.api.v2.recipe;
                import buildcraft.api.v2.fluid.*;
                public record DistillationRecipeDefinition(FluidIngredient input, FluidVolume gasOutput,
                       FluidVolume liquidOutput, long powerRequiredMicroMj) {}
            ''',
            "buildcraft/api/v2/recipe/RecipeMatch.java": '''
                package buildcraft.api.v2.recipe;
                public record RecipeMatch<T>(String id,T recipe,Object provenance) {}
            ''',
            "TestIdentity.java": '''
                import buildcraft.api.v2.fluid.*;
                import buildcraft.api.v2.recipe.*;
                import buildcraft.lib.recipe.DistillationProgressIdentity;
                public class TestIdentity {
                    static RecipeMatch<DistillationRecipeDefinition> recipe(String id, long gas, long power) {
                        var input = new FluidIngredient(new FluidVariant(12),new FluidAmount(1000));
                        var out = new FluidVolume(new FluidVariant(gas),new FluidAmount(500));
                        return new RecipeMatch<>(id,new DistillationRecipeDefinition(input,out,
                            new FluidVolume(null,new FluidAmount(0)),power),null);
                    }
                    public static void main(String[] args) {
                        var a=DistillationProgressIdentity.of(recipe("test:distiller/a",11,100));
                        var same=DistillationProgressIdentity.of(recipe("test:distiller/a",11,100));
                        if(!a.equals(same)) throw new AssertionError("fresh equal definitions are not stable");
                        if(a.equals(DistillationProgressIdentity.of(recipe("test:distiller/b",11,100))))
                            throw new AssertionError("recipe id not counted");
                        if(a.equals(DistillationProgressIdentity.of(recipe("test:distiller/a",13,100))))
                            throw new AssertionError("recipe output not counted");
                        if(a.equals(DistillationProgressIdentity.of(recipe("test:distiller/a",11,150))))
                            throw new AssertionError("MJ cost not counted");
                        if(!a.equals(DistillationProgressIdentity.fromSaved(a)))
                            throw new AssertionError("stable identity lost across save");
                        if(!DistillationProgressIdentity.fromSaved("x|dynamic@1").isEmpty())
                            throw new AssertionError("dynamic work must be revalidated");
                        if(!DistillationProgressIdentity.of(null).isEmpty())
                            throw new AssertionError("missing recipe must clear identity");
                        System.out.println("Distiller progress fingerprint PASS");
                    }
                }
            ''',
        }
        with tempfile.TemporaryDirectory(prefix="bcce-recipe-progress-") as td:
            base = Path(td)
            files = [src]
            for name, data in cases.items():
                target = base / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(textwrap.dedent(data), encoding="utf-8")
                files.append(target)
            classes = base / "classes"
            run = subprocess.run(["javac", "--release", "17", "-d", str(classes), *map(str, files)],
                                 capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            executed = subprocess.run(["java", "-cp", str(classes), "TestIdentity"],
                                      capture_output=True, text=True, timeout=10)
            self.assertEqual(executed.returncode, 0, executed.stderr)
            self.assertIn("Distiller progress fingerprint PASS", executed.stdout)

    # Recipe variant identity
    def test_programming_cost_is_not_collapsed_by_board_key(self):
        for family, prefix in (("old", "recipes"), ("1.21.X", "recipe"), ("26.X", "recipe")):
            recipes = list((ROOT / f"source-families/{family}/src/main/resources/data/buildcraftrobotics/{prefix}/programming_table").glob("*.json"))
            miner = next(json.loads(p.read_text(encoding="utf-8")) for p in recipes if p.stem == 'miner')
            self.assertEqual(miner['board_key'], 'miner')
            self.assertEqual(miner['energy']['mj'], 3200)
            # Same output, different MJ, both distinct in *effective* definition.
            signatures = {(r['board_key'], r['energy']['mj']) for r in (miner, {'board_key':'miner','energy':{'mj':1}})}
            self.assertEqual(len(signatures), 2)
            # An exact duplicate across two different ids reduces to one option.
            self.assertEqual(len(signatures | {(miner['board_key'],miner['energy']['mj'])}), 2)
        access = (ROOT / 'source-shared/src/main/java/buildcraft/lib/recipe/ProgrammingRecipeAccess.java').read_text(encoding="utf-8")
        self.assertIn('recipes.stream().distinct()', access)
        self.assertIn('ProgrammingRecipeDefinition::requiredMicroJoules', access)
        self.assertIn('ProgrammingRecipeDefinition::boardKey', access)
        self.assertIn('public static java.util.Optional<CostRange> costRange(String boardKey, boolean client)', access)
        self.assertIn('min = Math.min(min, energy);', access)
        self.assertIn('max = Math.max(max, energy);', access)
        self.assertIn('return max == 0 ? java.util.Optional.empty()', access)
        reload_code = (ROOT / 'source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java').read_text(encoding="utf-8")
        self.assertIn('Map<ProgrammingRecipeDefinition, SourceFile> identicalProgramming', reload_code)
        self.assertIn('Duplicate programming recipe content at', reload_code)
        self.assertNotIn('Two programming recipes target board key', reload_code)

    def test_all_table_implementations_execute_selected_variant(self):
        for family in ('source-platforms/forge', 'source-families/1.21.X'):
            p = ROOT / family / 'src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java'
            with self.subTest(family=family):
                s = p.read_text(encoding="utf-8")
                self.assertIn('optionDefinitions.get(absolute).requiredMicroJoules()', s)
                self.assertIn('java.util.Set<ProgrammingRecipeDefinition> seen', s)
                self.assertIn('syncedProgrammingOptions = List.copyOf(recipes)', s)
                self.assertNotIn('costs.putIfAbsent(key, energy)', s)

    def test_seven_jei_plugins_show_distinct_programming_recipes_and_correct_grid(self):
        plugins = jei_plugin_sources()
        self.assertEqual(len(plugins), 7)
        for p in plugins:
            with self.subTest(plugin=p):
                s = p.read_text(encoding="utf-8")
                self.assertIn('record ProgrammingRecipeView(BoardEntry board, long requiredMicroJoules)', s)
                self.assertIn('getSortedProgrammingRecipes().indexOf(recipe)', s)
                self.assertNotIn('optionBoards.indexOf(recipe.board())', s)
                self.assertIn('int cell = index < 4 ? index : index + 1;', s)
                self.assertIn('int[] columns = {2, 28, 53};', s)
                self.assertIn('int[] rows = {2, 27, 52};', s)
                self.assertIn('int x = columns[cell % 3];', s)
                self.assertIn('int y = rows[cell / 3];', s)
                self.assertNotIn('int x = 9 + (cell % 3) * 18;', s)
                self.assertNotIn('int y = 9 + (cell / 3) * 18;', s)
                self.assertNotIn('ProgrammingRecipeAccess.unambiguousCost(', s)
                self.assertIn('getSortedProgrammingRecipes()', s)
                self.assertIn('getProgressBar(recipe.requiredMicroJoules())', s)
                self.assertIn('formatMj(recipe.requiredMicroJoules())', s)
                self.assertNotIn('addTooltipCallback((view, tooltip)', s)
                self.assertNotIn('int x = 48 + (index % 4) * 18;', s)

    def test_recipe_center_is_not_shifted_and_secondaries_fit_real_background(self):
        # Integration JEI texture crop contains a 3x3 layout with irregular spacing.
        columns = (2, 28, 53)
        rows = (2, 27, 52)
        occupied = [(columns[i % 3], rows[i // 3]) for i in range(9) if i != 4]
        self.assertEqual(occupied, [
            (2, 2), (28, 2), (53, 2),
            (2, 27), (53, 27),
            (2, 52), (28, 52), (53, 52),
        ])
        self.assertEqual(len(set(occupied)), 8)

    def test_board_tooltips_do_not_claim_cheapest_price_for_all_variants(self):
        sources = [
            'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/item/ItemRedstoneBoard.java',
            'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/item/ItemRedstoneBoard.java',
            'source-families/1.21.X/src/main/java/buildcraft/robotics/item/ItemRedstoneBoard.java',
            'source-families/old/src/main/java/buildcraft/robotics/BCRoboticsBoards.java',
            'source-families/1.21.X/src/main/java/buildcraft/robotics/BCRoboticsBoards.java',
        ]
        for source in sources:
            with self.subTest(source=source):
                text = (ROOT / source).read_text(encoding="utf-8")
                self.assertIn('formatProgrammingCostTooltip(', text)
                self.assertNotIn('ProgrammingRecipeAccess.costs(true).getOrDefault(', text)
        for family in ('old', '1.21.X'):
            boards = (ROOT / f'source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java').read_text(encoding="utf-8")
            self.assertIn('ProgrammingRecipeAccess.costRange(boardKey, client)', boards)
            self.assertIn('cost.minMicroMj() == cost.maxMicroMj()', boards)
            self.assertIn('minimum + " – " + formatBoardEnergyCostMicro(cost.maxMicroMj())', boards)
            self.assertIn('BCLibConfig.hidePowerValues', boards)

    def test_blacklist_id_is_not_interpreted_as_one_robot_variant(self):
        # The built-in descriptor can still be blacklisted; that bans every
        # robot-board variant by design, since they share an ID.
        doc = (ROOT / 'docs/recipe_docs.md').read_text(encoding="utf-8")
        self.assertIn('disables **all** robot-board integration variants', doc)

    # Recipe runtime recovery
    def test_secondary_integration_grid_is_shifted_one_pixel(self):
        plugins = jei_plugin_sources()
        self.assertEqual(len(plugins), 7)
        for path in plugins:
            with self.subTest(path=path):
                source = path.read_text(encoding="utf8")
                self.assertIn("int[] columns = {2, 28, 53};", source)
                self.assertIn("int[] rows = {2, 27, 52};", source)
                self.assertIn('ClientMachineRecipeCatalog.isDenied("integration_table", robotRecipeId)', source)
                self.assertIn('instanceof buildcraft.robotics.recipes.RobotIntegrationRecipe', source)
                self.assertIn("unhideRecipes(type,", source)
                self.assertIn("List<T> removed = previous.stream()", source)
                self.assertIn("List<T> introduced =", source)

    def test_distiller_refund_escrow_does_not_suspend_work(self):
        for platform in ("forge", "neoforge"):
            for cls in ("TileDistiller.java", "TileDistiller_BC8.java"):
                path = ROOT / "source-platforms" / platform / "src/main/java/buildcraft/factory/tile" / cls
                source = path.read_text(encoding="utf8")
                with self.subTest(platform=platform, cls=cls):
                    body = source.split("    public void update()",1)[1].split("    private void queueProgressRefund",1)[0]
                    self.assertNotIn("if (pendingPowerRefund > 0)", body)
                    self.assertNotIn("if (!refundPendingPower())", body)
                    self.assertIn("refundPendingPower();", body)
                    self.assertIn("MachineRecipeApiBridge.findDistillationMatch(tankIn.getFluid())", body)
                    self.assertGreaterEqual(body.count("refundPendingPower();"), 2)

    def test_full_battery_refund_can_clear_after_next_operation(self):
        capacity = 1024 * 1_000_000
        stored = capacity
        pending = 5 * 1_000_000
        # An idle full buffer cannot take a refund; that is not a reason to stop.
        first_refund = min(pending, capacity - stored)
        self.assertEqual(first_refund, 0)
        # A new process spends MJ, leaving space to settle old paid progress.
        consumed_for_new_work = 6 * 1_000_000
        stored -= consumed_for_new_work
        refunded = min(pending, capacity - stored)
        stored += refunded
        pending -= refunded
        self.assertEqual(pending, 0)
        self.assertEqual(stored, capacity - 1_000_000)

    def test_broadcast_only_from_authoritative_server_thread(self):
        source = (ROOT / "source-shared/src/main/java/buildcraft/lib/net/MessageMachineRecipes.java").read_text(encoding="utf-8")
        self.assertIn("if (!MessageManager.isAuthoritativeServerThread()) return;", source)
        managers = message_manager_sources()
        self.assertEqual(len(managers), 5)
        for manager in managers:
            code = manager.read_text(encoding="utf-8")
            self.assertIn("boolean isAuthoritativeServerThread()", code)
            self.assertIn("server != null && server.isSameThread()", code)

    def test_vanilla_neoforge_mirrors_not_logged_as_190_conflicts(self):
        source = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text(encoding="utf-8")
        self.assertIn("isBuiltinNeoForgeOverlay(fileId, entry.getValue())", source)
        self.assertIn('packs.equals(Set.of("vanilla", "mod/neoforge"))', source)
        self.assertIn('warnings.add("Duplicate recipe ID "', source)

if __name__ == "__main__":
    unittest.main()
