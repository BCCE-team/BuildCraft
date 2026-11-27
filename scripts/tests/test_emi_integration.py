"""Source contracts for the optional native EMI integration (not Minecraft runtime tests)."""
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
PLUGIN = 'src/main/java/buildcraft/compat/emi/BuildCraftEmiPlugin.java'

class EmiIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = {target: read_materialized_java(target, PLUGIN) for target in TARGETS}

    def test_assembly_fixed_output_fallback_and_hdd_recipe(self):
        # Legacy fixed-output recipes deliberately expose no multi-output previews.
        # Run the actual materialized EMI helper against small Java API stubs.
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
public class EmiPreviewProbe {
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
                with tempfile.TemporaryDirectory(prefix='bcce-emi-preview-') as tmp:
                    path = Path(tmp) / 'EmiPreviewProbe.java'
                    path.write_text(fixture, encoding='utf-8')
                    subprocess.run(['javac', str(path)], cwd=tmp, check=True, capture_output=True, text=True)
                    subprocess.run(['java', '-cp', tmp, 'EmiPreviewProbe'], cwd=tmp, check=True, capture_output=True, text=True)

    def test_native_categories_and_catalog(self):
        for target, code in self.sources.items():
            with self.subTest(target=target):
                self.assertIn('implements EmiPlugin', code)
                self.assertNotIn('import mezz.jei', code)
                self.assertNotIn('import me.shedaniel.rei', code)
                for name in ('assembly', 'programming', 'integration', 'distillation', 'heatExchange', 'combustion'):
                    self.assertIn(name + ' = category(registry,', code)
                for kind in ('ProgrammingRecipeDefinition', 'StaticIntegrationRecipe', 'DistillationRecipeDefinition', 'HeatExchangeRecipeDefinition'):
                    self.assertIn('definitions(' + kind + '.class)', code)
                self.assertIn('registry.addDeferredRecipes', code)
                self.assertIn('snapshot.authoritative()', code)
                self.assertIn('!snapshot.isDenied(entry.machine(), entry.id())', code)
                self.assertIn('ClientMachineRecipeCatalog.setEmiListener', code)
                self.assertIn('EmiReloadManager.reload()', code)
                for method in ('recipeEnabled', 'fuelEnabled', 'fluidEnabled', 'bucketEnabled'):
                    self.assertIn('OilIndustry.' + method, code)
                self.assertIn('UUID.nameUUIDFromBytes', code)
                self.assertIn('registry.addGenericExclusionArea', code)
        sync = (ROOT / 'source-shared/src/main/java/buildcraft/lib/recipe/ClientMachineRecipeCatalog.java').read_text()
        self.assertRegex(sync, r'notifyRei\(\);\s*notifyEmi\(\);')
        self.assertIn('emiListener = null;', sync)
        for folder in ('1.21.X/neoforge', '26.X/fabric'):
            self.assertIn('ClientMachineRecipeCatalog.notifyEmi()', (ROOT / 'source-family-platforms' / folder / 'src/main/java/buildcraft/silicon/BCSiliconRecipeSync.java').read_text())

    def test_fluid_units_and_data(self):
        for target, code in self.sources.items():
            method = re.search(r'private static long nativeFluidAmount\(long mb\) \{.*?\n    \}', code, re.S).group()
            self.assertIn('return mb * 81L;' if target.endswith('-fabric') else 'return mb;', method)
            self.assertIn('stack.getTag()' if target.endswith('-forge') else 'stack.getComponentsPatch()', code)
            self.assertIn('entries.add(outputFluid(variant))', code)

    def test_transfers_and_pipe_replacement(self):
        for code in self.sources.values():
            for token in ('StandardRecipeHandler<ContainerAssemblyTable>', 'menu.slots.subList(36, 48)', 'PhantomHandler<ContainerAutoCraftItems>', 'PhantomHandler<ContainerAdvancedCraftingTable>', 'menu.sendSetPhantomSlots(menu.blueprintInv, grid)', 'crafting.canFit(3, 3)', 'registry.removeRecipes(existing -> existing != replacement', 'registry.addRecipe(replacement)', '!pipe.hasShapedBasePattern()'):
                self.assertIn(token, code)

    def test_optional_dependencies(self):
        props = load_properties()
        self.assertEqual(props['common.recipe_viewer_mod'], 'jei')
        for target in TARGETS:
            dep = props.get('target.' + target + '.deps.emi', '')
            self.assertEqual(bool(dep), target != '1.21.11-neoforge')
            if dep:
                self.assertTrue(dep.startswith('maven.modrinth:'))
        for loader in ('forge','neoforge','fabric'):
            code = (ROOT / 'build-logic/loaders' / (loader+'-target.gradle')).read_text()
            self.assertRegex(code, r'compileOnly[^\n]+(?:emiDependency|deps\.emi)')
            self.assertTrue("selectedRecipeViewer == 'emi'" in code or "recipeViewerRuntimeEnabled('emi')" in code)
        meta = json.loads((ROOT / 'source-family-platforms/26.X/fabric/src/main/resources/fabric.mod.json').read_text())
        self.assertEqual(meta['entrypoints']['emi'], ['buildcraft.compat.emi.BuildCraftEmiPlugin'])
        self.assertNotIn('emi', meta['depends'])

if __name__ == '__main__':
    unittest.main()
