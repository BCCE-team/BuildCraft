"""Phase-4 recipe visibility and paid-progress safety regressions."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RecipePhaseFourTest(unittest.TestCase):
    def test_static_integration_jei_on_all_seven_targets(self):
        files = sorted(ROOT.rglob("BuildCraftJeiPlugin.java"))
        self.assertEqual(len(files), 7)
        for p in files:
            with self.subTest(target=p):
                text = p.read_text()
                self.assertEqual(text.count("collectIntegrationRecipes();"), 2)
                self.assertIn("ClientMachineRecipeCatalog.visibleRecipes(StaticIntegrationRecipe.class)", text)
                self.assertIn("integrationIngredientStacks(fixed.centerIngredient())", text)
                self.assertIn("integrationIngredientStacks(additional.get(index))", text)
                if "version-src/" in str(p) or "1.21.1/neoforge/" in str(p):
                    self.assertIn("ingredient.ingredient().getItems()", text)
                else:
                    self.assertIn("IngredientCompat.getItems(ingredient.ingredient())", text)
                self.assertIn("builder.addSlot(RecipeIngredientRole.OUTPUT, 121, 27)", text)
                self.assertIn("fixed.requiredMicroJoules(fixed.result())", text)
                self.assertNotIn('isDenied("programming_table",\n', text)
        recipe = (ROOT / "source-shared/src/main/java/buildcraft/api/v2/recipe/StaticIntegrationRecipe.java").read_text()
        self.assertIn("public ItemStack result() { return result.copy(); }", recipe)

    def test_board_permissions_use_actual_recipe_ids(self):
        for folder in ("source-platforms/forge", "source-families/1.21.X"):
            code = (ROOT / folder / "src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java").read_text()
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
        access = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/ProgrammingRecipeAccess.java").read_text()
        self.assertIn("MachineRecipeService", (ROOT / "source-shared/src/main/java/buildcraft/api/v2/recipe/MachineRecipeService.java").read_text())
        self.assertIn("recipes(ProgrammingRecipeDefinition.class)", access)

    def test_modern_machine_recipe_compile_compatibility(self):
        # The 1.21.11 and 26.X mappings use Identifier, not ResourceLocation.
        recipe_id = '"buildcraftrobotics:integration_table/robot_board_integration"'
        for family in ("1.21.X", "26.X"):
            path = (ROOT / f"source-family-platforms/{family}/neoforge/src/main/java/"
                    "buildcraft/compat/jei/BuildCraftJeiPlugin.java")
            with self.subTest(family=family):
                source = path.read_text()
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
                source = path.read_text()
                self.assertIn('import net.minecraft.resources.Identifier;', source)
                self.assertGreaterEqual(source.count('Identifier.fromNamespaceAndPath('
                                               '"buildcraftrobotics", "integration_table/robot_board_integration")'), 1)
                self.assertIn('"buildcraftrobotics", "integration_table/robot_board_integration"', source)
                self.assertNotIn('net.minecraft.resources.ResourceLocation.tryParse(' + recipe_id + ')', source)

        # 1.21.1 still uses ResourceLocation; leave its downport untouched.
        old_jei = (ROOT / "source-downports/1.21.X/1.21.1/neoforge/src/main/java/"
                   "buildcraft/compat/jei/BuildCraftJeiPlugin.java").read_text()
        self.assertGreaterEqual(old_jei.count('ResourceLocation.tryParse(' + recipe_id + ')'), 1)

        # In 1.21.11 CustomRecipe#getSerializer has a covariant return
        # RecipeSerializer<? extends CustomRecipe>, unlike RecipeSerializer<?>.
        for file in ("factory/BCFactoryRecipeSerializers.java", "silicon/BCSiliconRecipes.java"):
            path = ROOT / "source-families/1.21.X/src/main/java/buildcraft" / file
            with self.subTest(file=file):
                source = path.read_text()
                self.assertIn('RecipeSerializer<? extends CustomRecipe> getSerializer()', source)
                self.assertNotIn('RecipeSerializer<?> getSerializer()', source)

    def test_distiller_identity_persisted_all_implementations(self):
        for folder in ("source-platforms/forge", "source-platforms/neoforge"):
            for name in ("TileDistiller.java", "TileDistiller_BC8.java"):
                p = ROOT / folder / "src/main/java/buildcraft/factory/tile" / name
                text = p.read_text()
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
            text = (ROOT / folder / "src/main/java/buildcraft/lib/recipe/MachineRecipeApiBridge.java").read_text()
            self.assertIn("RecipeMatch<DistillationRecipeDefinition> findDistillationMatch", text)

    def test_distiller_nbt_uses_cross_version_persistence_facade(self):
        # 1.21.1 deliberately excludes NbtCompat; 1.21.11 and 26.X include it.
        # Reading via BCValueInput works on all of these targets.
        reader = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft/persistence/BCValueInput.java").read_text()
        accessor = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft/persistence/TagAccess.java").read_text()
        compat = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/compat/NbtCompat.java").read_text()
        self.assertIn("public String readString(String key) { return TagAccess.getString(tag, key); }", reader)
        self.assertIn("static String getString(CompoundTag tag, String key)", accessor)
        self.assertIn("//? source if >=1.21.11", compat)
        for name in ("TileDistiller.java", "TileDistiller_BC8.java"):
            file = ROOT / "source-platforms/neoforge/src/main/java/buildcraft/factory/tile" / name
            source = file.read_text()
            with self.subTest(file=file):
                self.assertIn('nbt.putString("distillRecipeKey", inFlightRecipeKey)', source)
                self.assertIn('bcData.readString("distillRecipeKey")', source)
                self.assertNotIn('NbtCompat.getString(nbt, "distillRecipeKey")', source)

    def test_only_active_minecraft_recipe_folder_is_loaded(self):
        loader = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text()
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
                target.write_text(textwrap.dedent(data))
                files.append(target)
            classes = base / "classes"
            run = subprocess.run(["javac", "--release", "17", "-d", str(classes), *map(str, files)],
                                 capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            executed = subprocess.run(["java", "-cp", str(classes), "TestIdentity"],
                                      capture_output=True, text=True, timeout=10)
            self.assertEqual(executed.returncode, 0, executed.stderr)
            self.assertIn("Distiller progress fingerprint PASS", executed.stdout)


if __name__ == "__main__":
    unittest.main()
