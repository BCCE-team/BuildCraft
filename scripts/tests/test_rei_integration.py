"""Source contracts for the optional native REI integration (not Minecraft runtime tests)."""
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties
from source_lookup import read_materialized_java, resolve_target_source

TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
           '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
PLUGIN = 'src/main/java/buildcraft/compat/rei/BuildCraftReiPlugin.java'

class ReiIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {target: read_materialized_java(target, PLUGIN) for target in TARGETS}

    def test_every_target_has_native_categories(self):
        for target, source in self.sources.items():
            with self.subTest(target=target):
                self.assertIn('implements REIClientPlugin', source)
                self.assertNotIn('import mezz.jei', source)
                for name in ('ASSEMBLY', 'PROGRAMMING', 'INTEGRATION', 'DISTILLATION', 'HEAT_EXCHANGE', 'COMBUSTION_FUEL'):
                    self.assertIn('category(registry, ' + name, source)
                    self.assertIn('dynamic(registry, ' + name, source)
                self.assertEqual('fabric' not in target, '@me.shedaniel.rei.forge.REIPluginClient' in source)
                self.assertEqual(target in TARGETS[:3], 'registerRecipeFiller(PipeRecipe.class' in source)
                self.assertEqual(target not in TARGETS[:3], 'getSerializer()' in source)

    def test_authoritative_recipe_sources_and_oil_filter(self):
        for source in self.sources.values():
            self.assertIn('ClientMachineRecipeCatalog.visibleRecipes(DistillationRecipeDefinition.class)', source)
            self.assertIn('ClientMachineRecipeCatalog.visibleRecipes(HeatExchangeRecipeDefinition.class)', source)
            self.assertIn('ClientMachineRecipeCatalog.visibleRecipes(StaticIntegrationRecipe.class)', source)
            self.assertIn('ProgrammingRecipeAccess.options(true)', source)
            self.assertIn('ClientMachineRecipeCatalog.isDenied("assembly_table"', source)
            self.assertIn('BuildCraftServices.ENERGY_FLUIDS).fuels()', source)
            self.assertIn('OilIndustry.bucketEnabled', source)
            self.assertIn('OilIndustry.fluidEnabled', source)
            self.assertIn('ClientMachineRecipeCatalog.setReiListener', source)
            self.assertIn('FluidStack.bucketAmount() / 1000', source)

    def test_assembly_fixed_output_fallback_and_hdd_recipe(self):
        # Legacy fixed-output recipes deliberately expose no multi-output previews.
        # Run the actual materialized REI helper against small Java API stubs.
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java is required for the recipe preview probe')
        for target, source in self.sources.items():
            with self.subTest(target=target):
                legacy = target in TARGETS[:3]
                resource_folder = 'recipes' if target.endswith('-forge') else 'recipe'
                recipe = json.loads(resolve_target_source(target,
                    f'src/main/resources/data/buildcraftrobotics/{resource_folder}/assembly/hard_disk.json').read_text())
                self.assertEqual('buildcraftrobotics:hard_disk', recipe['result']['item'])
                self.assertEqual([2, 1, 1, 1], recipe['ingredient_counts'])
                self.assertEqual(12_000_000_000, recipe['MJ'])
                self.assertIn('for (ItemStack output : assemblyOutputs(recipe))', source)
                match = re.search(r'private static List<ItemStack> assemblyOutputs\(AssemblyRecipeBasic recipe\) \{.*?\n    \}', source, re.S)
                self.assertIsNotNone(match)
                helper = match.group()
                self.assertEqual(legacy, 'recipe.getResultItem(' in helper)
                if target == '1.19.2-forge':
                    result_accessor = 'ItemStack getResultItem() { return result; }'
                elif legacy:
                    result_accessor = 'ItemStack getResultItem(Object registries) { return result; }'
                else:
                    result_accessor = ''
                fixture = r'''import java.util.*;
public class ReiPreviewProbe {
    record ItemStack(String id, int data) { boolean isEmpty() { return id.isEmpty(); } }
    static class Minecraft {
        static Minecraft getInstance() { return new Minecraft(); }
        Level level = new Level();
    }
    static class Level { Object registryAccess() { return new Object(); } }
    record AssemblyRecipeBasic(Set<ItemStack> previews, ItemStack result) {
        Set<ItemStack> getOutputPreviews() { return previews; }
        RESULT_ACCESSOR
    }
    HELPER
    public static void main(String[] args) {
        ItemStack hdd = new ItemStack("buildcraftrobotics:hard_disk", 0);
        ItemStack gate = new ItemStack("buildcraftsilicon:plug/gate", 193);
        ItemStack empty = new ItemStack("", 0);
        if (!assemblyOutputs(new AssemblyRecipeBasic(Set.of(gate), hdd)).equals(List.of(gate)))
            throw new AssertionError("Existing previews must win and preserve item data");
        Set<ItemStack> hddPreviews = LEGACY ? Set.of() : Set.of(hdd);
        if (!assemblyOutputs(new AssemblyRecipeBasic(hddPreviews, hdd)).equals(List.of(hdd)))
            throw new AssertionError("HDD recipe disappeared");
        if (!assemblyOutputs(new AssemblyRecipeBasic(Set.of(empty), empty)).isEmpty())
            throw new AssertionError("Empty output must remain hidden");
    }
}
'''.replace('RESULT_ACCESSOR', result_accessor).replace('HELPER', helper).replace('LEGACY', str(legacy).lower())
                with tempfile.TemporaryDirectory(prefix='bcce-rei-preview-') as tmp:
                    path = Path(tmp) / 'ReiPreviewProbe.java'
                    path.write_text(fixture, encoding='utf-8')
                    subprocess.run(['javac', str(path)], cwd=tmp, check=True, capture_output=True, text=True)
                    subprocess.run(['java', '-cp', tmp, 'ReiPreviewProbe'], cwd=tmp, check=True, capture_output=True, text=True)

    def test_workstations_and_transfer(self):
        for source in self.sources.values():
            self.assertIn('registry.addWorkstations(BuiltinPlugin.CRAFTING', source)
            self.assertIn('registry.addWorkstations(BuiltinPlugin.FUEL', source)
            self.assertIn('SimpleTransferHandler.IntRange(36, 48)', source)
            self.assertIn('menu.sendSetPhantomSlots(menu.blueprintInv, grid)', source)
            self.assertIn('shaped.getInputWidth(3, 3)', source)
            self.assertIn('grid.set((i / width) * 3 + i % width, copy)', source)

    def test_runtime_selection_and_pinned_dependencies(self):
        props = load_properties()
        self.assertEqual(props['common.recipe_viewer_mod'], 'jei')
        for target in TARGETS:
            for key in ('rei','rei_architectury','rei_cloth'):
                self.assertTrue(props['target.' + target + '.deps.' + key].startswith('maven.modrinth:'))
            if target != '1.21.11-neoforge':
                self.assertTrue(props['target.' + target + '.deps.emi'].startswith('maven.modrinth:'))
        common = (ROOT / 'build-logic/common-target.gradle').read_text()
        self.assertIn("requiredProperty('recipe_viewer_mod')", common)
        self.assertIn("['jei', 'rei', 'emi']", common)
        for loader in ('forge','neoforge','fabric'):
            code = (ROOT / 'build-logic/loaders' / (loader+'-target.gradle')).read_text()
            self.assertIn('bc.recipeViewerMod', code)
            self.assertIn("['deps.rei', 'deps.rei_architectury', 'deps.rei_cloth']", code)
            self.assertIn("'deps.emi'", code)

    def test_fabric_entrypoints(self):
        meta = json.loads((ROOT / 'source-family-platforms/26.X/fabric/src/main/resources/fabric.mod.json').read_text())
        self.assertEqual(meta['entrypoints']['rei_client'], ['buildcraft.compat.rei.BuildCraftReiPlugin'])
        self.assertEqual(meta['entrypoints']['rei_common'], ['buildcraft.compat.rei.BuildCraftReiCommonPlugin'])
        self.assertNotIn('roughlyenoughitems', meta['depends'])

if __name__ == '__main__':
    unittest.main()
