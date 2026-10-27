#!/usr/bin/env python3
"""Silicon recipe discovery, display, and selection regression tests."""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import load_properties, materialize_target


class SiliconRecipeBookSweep(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bc-silicon-recipe-book-")
        cls.props = load_properties()
        cls.roots: dict[str, Path] = {}
        for target in ("1.21.1-neoforge", "1.21.11-neoforge"):
            path = Path(cls.temp.name) / target
            materialize_target(target, path, cls.props)
            cls.roots[target] = path

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def java(self, target: str, logical: str) -> str:
        return (self.roots[target] / "src/main/java" / logical).read_text()

    def test_assembly_gui_does_not_require_client_recipe_manager(self):
        for target in self.roots:
            tile = self.java(target, "buildcraft/silicon/tile/TileAssemblyTable.java")
            self.assertIn("createSyncedInstruction", tile, target)
            self.assertIn("side == BCNetworkSide.CLIENT", tile, target)
            self.assertIn("new AssemblyInstruction(recipeId, null, output.copy())", tile, target)
            self.assertIn("instruction == null || instruction.recipe == null", tile, target)
            self.assertIn("lookupServerRecipe", tile, target)

            # NET_GUI_DATA is server-authoritative UI state. Reconstructing it must not try to
            # resolve RecipeManager on the logical client (1.21.11 no longer exposes that model).
            gui_start = tile.index("if (id == NET_GUI_DATA)")
            recipe_state = tile.index("if (id == NET_RECIPE_STATE)", gui_start)
            gui_read = tile[gui_start:recipe_state]
            self.assertIn("createSyncedInstruction(recipeName, output)", gui_read, target)
            self.assertNotIn("getRecipeManager().byKey", gui_read, target)
            self.assertNotIn("ServerLevel", gui_read, target)

    def test_assembly_state_only_changes_are_synchronised(self):
        for target in self.roots:
            tile = self.java(target, "buildcraft/silicon/tile/TileAssemblyTable.java")
            self.assertIn("boolean changed = false;", tile, target)
            self.assertIn("EnumAssemblyRecipeState previousState = entry.getValue();", tile, target)
            self.assertIn("if (state != previousState)", tile, target)
            self.assertIn("changed = true;", tile, target)
            self.assertIn("if (changed)", tile, target)
            self.assertIn("sendNetworkGuiUpdate(NET_GUI_DATA);", tile, target)
            # Clicking a recipe gets an immediate authoritative echo, not a stale icon until
            # enough MJ happens to make the normal laser-table tick send another packet.
            state_start = tile.index("if (id == NET_RECIPE_STATE)")
            state_end = tile.index("public void sendRecipeStateToServer", state_start)
            state_block = tile[state_start:state_end]
            self.assertIn("side == BCNetworkSide.SERVER", state_block, target)
            self.assertIn("sendNetworkGuiUpdate(NET_GUI_DATA);", state_block, target)

    def test_integration_preview_is_server_authoritative(self):
        for target in self.roots:
            tile = self.java(target, "buildcraft/silicon/tile/TileIntegrationTable.java")
            menu = self.java(target, "buildcraft/silicon/container/ContainerIntegrationTable.java")
            self.assertIn("private ItemStack syncedOutput = ItemStack.EMPTY;", tile, target)
            self.assertIn("level != null && level.isClientSide", tile, target)
            self.assertIn("return syncedOutput;", tile, target)
            self.assertIn("ItemStackUtil.writeOptional(buffer, getOutput());", tile, target)
            self.assertIn("syncedOutput = ItemStackUtil.readOptional(buffer);", tile, target)
            self.assertIn("new SlotDisplay(invOutput, 0, 101, 36)", menu, target)

    def test_programming_discovery_display_and_selection_remain_server_validated(self):
        for target in self.roots:
            tile = self.java(target, "buildcraft/silicon/tile/TileProgrammingTable_Neptune.java")
            menu = self.java(target, "buildcraft/silicon/container/ContainerProgrammingTable.java")
            gui = self.java(target, "buildcraft/silicon/gui/GuiProgrammingTable.java")

            self.assertIn("OPTION_COUNT = WIDTH * HEIGHT", tile, target)
            self.assertIn("private List<ProgrammingRecipeDefinition> cachedRecipeCatalog = List.of();", tile, target)
            # The runtime preserves per-board variants at different MJ costs and
            # rebuilds its immutable choices when the authoritative catalog changes.
            refresh_start = tile.index("private void refreshOptions()")
            refresh_end = tile.index("public long getTarget()", refresh_start)
            refresh = tile[refresh_start:refresh_end]
            self.assertIn("BCRoboticsBoards.init();", refresh, target)
            self.assertIn("if (!canProgramInput()) {\n            options = List.of();\n            return;\n        }", refresh, target)
            self.assertIn("ProgrammingRecipeAccess.options(client)", refresh, target)
            self.assertIn(".filter(recipe -> BCRoboticsBoards.getByKey(recipe.boardKey()) != BCRoboticsBoards.EMPTY)", refresh, target)
            self.assertIn(".distinct()", refresh, target)
            self.assertIn("if (!active.equals(cachedRecipeCatalog) || options.isEmpty())", refresh, target)
            self.assertIn("optionDefinitions = active;", refresh, target)
            self.assertIn("options = active.stream().map(recipe -> BCRoboticsBoards.getByKey(recipe.boardKey())).toList();", refresh, target)
            self.assertIn("optionDefinitions.get(absolute).requiredMicroJoules()", tile, target)
            self.assertIn("if (selectedPage < 0 || selectedPage >= pageCount) selectedPage = 0;", refresh, target)
            self.assertIn("selectedPage * OPTION_COUNT + selectedOption >= options.size()", refresh, target)
            self.assertIn("refreshOptions();\n        return options;", tile, target)
            # The programming table displays one page at a time. An option is a slot
            # on the current page, while page-change messages use negative indices.
            self.assertIn("if (option <= -2)", tile, target)
            self.assertIn("long requested = -((long) option) - 2L;", tile, target)
            self.assertIn("if (requested < 0 || requested >= pageCount()) return;", tile, target)
            self.assertIn("selectedPage = (int) requested;", tile, target)
            self.assertIn("option < 0 || option >= OPTION_COUNT || selectedPage * OPTION_COUNT + option >= opts.size()", tile, target)
            self.assertIn("if (selectedOption < 0 || absolute < 0 || absolute >= opts.size())", tile, target)
            self.assertIn("return absolute >= 0 && absolute < opts.size()", tile, target)
            self.assertIn("selectedOption = -1;", tile, target)
            self.assertIn("sendNetworkGuiUpdate(NET_GUI_DATA);", tile, target)

            self.assertIn("new SlotDisplay(options, index", menu, target)
            self.assertIn("sendSelectOption", menu, target)
            self.assertIn("if (page >= 0) sendSelectOption(-page - 2);", menu, target)
            self.assertIn("side == BCNetworkSide.SERVER", menu, target)
            self.assertIn("tile.selectOption(buffer.readVarInt())", menu, target)
            self.assertIn("container.sendSelectOption(container.tile.selectedOption == i ? -1 : i)", gui, target)
            self.assertIn("container.sendSelectPage(container.tile.selectedPage - 1)", gui, target)
            self.assertIn("container.sendSelectPage(container.tile.selectedPage + 1)", gui, target)

    def test_laser_targets_only_tables_with_real_work(self):
        for target in self.roots:
            laser = self.java(target, "buildcraft/silicon/tile/TileLaser.java")
            base = self.java(target, "buildcraft/silicon/tile/TileLaserTableBase.java")
            self.assertIn("laserTargets().target(level, p, laserTargetSide()).isPresent()", laser, target)
            self.assertIn("insert(MjAmount.ofMicro(getMaxPowerPerTick()), OperationMode.SIMULATE)", laser, target)
            self.assertIn("targetsNeedingPower.add(position)", laser, target)
            self.assertIn("level != null && level.isClientSide", base, target)
            self.assertIn("? targetClient : getTarget()", base, target)

    def test_1_21_11_phantom_recipe_book_keeps_reference_features(self):
        gui = self.java('1.21.11-neoforge', 'buildcraft/lib/gui/recipe/GuiRecipeBookPhantom.java')
        listing = self.java('1.21.11-neoforge', 'buildcraft/lib/gui/recipe/RecipeListPhantom.java')
        auto = self.java('1.21.11-neoforge', 'buildcraft/factory/gui/GuiAutoCraftItems.java')
        advanced = self.java('1.21.11-neoforge', 'buildcraft/silicon/gui/GuiAdvancedCraftingTable.java')

        self.assertIn('BCRecipeDisplays.unlockedCrafting(recipeBook)', listing)
        self.assertIn('EditBox searchBox', gui)
        self.assertIn('categoryTabs', gui)
        self.assertIn('BCGuiInput.key(searchBox', gui)
        self.assertIn('BCGuiInput.character(searchBox', gui)
        self.assertIn('entry.category()', gui)
        self.assertIn('return Optional.empty();', gui)
        # The 1.21.11 phantom backend uses the exact vanilla Recipe Book visual assets and coordinates.
        for token in ('textures/gui/recipe_book.png', 'recipe_book/tab_selected',
                      'recipe_book/page_forward', 'recipe_book/page_backward',
                      'PANEL_WIDTH = 147', 'PANEL_HEIGHT = 166'):
            self.assertIn(token, gui)
        button = self.java('1.21.11-neoforge', 'buildcraft/lib/gui/recipe/GuiButtonRecipePhantom.java')
        self.assertIn('recipe_book/slot_craftable', button)
        self.assertIn('recipe_book/slot_uncraftable', button)
        for screen in (auto, advanced):
            self.assertIn('new ImageButton(', screen)
            self.assertIn('RecipeBookComponent.RECIPE_BUTTON_SPRITES', screen)
            self.assertNotIn('Component.literal("R")', screen)
            self.assertIn('container.materialInv.getStackInSlot(slot)', screen)
            self.assertIn('contents.accountStack(stack)', screen)
        self.assertIn('import net.minecraft.world.item.ItemStack;', advanced)

    def test_legacy_assembly_state_only_changes_are_synchronised(self):
        for target in ('1.19.2-forge', '1.20.1-forge'):
            path = Path(self.temp.name) / ('legacy-' + target)
            materialize_target(target, path, self.props)
            tile = (path / 'src/main/java/buildcraft/silicon/tile/TileAssemblyTable.java').read_text(encoding='utf-8')
            self.assertIn('boolean changed = false;', tile, target)
            self.assertIn('EnumAssemblyRecipeState previousState = entry.getValue();', tile, target)
            self.assertIn('if (state != previousState)', tile, target)
            self.assertIn('if (changed)', tile, target)
            state_start = tile.index('if (id == NET_RECIPE_STATE)')
            state_end = tile.index('public void sendRecipeStateToServer', state_start)
            state_block = tile[state_start:state_end]
            self.assertIn('sendNetworkGuiUpdate(NET_GUI_DATA);', state_block, target)

    def test_1_21_11_recipe_sync_still_covers_assembly_definitions(self):
        sync = self.java("1.21.11-neoforge", "buildcraft/silicon/BCSiliconRecipeSync.java")
        self.assertIn("event.sendRecipes(BCSiliconRecipes.ASSEMBLY_TYPE.get())", sync)
        self.assertIn("event.getRecipeMap().byType(BCSiliconRecipes.ASSEMBLY_TYPE.get())", sync)
        self.assertIn("assembly = List.copyOf", sync)
        self.assertIn("assembly = List.of();", sync)




# ---- Lens recipes and result variants across targets ----



import json
import re
import subprocess

from source_layout import (
    load_properties, preprocess_text, resolve_effective_source,
    strip_source_condition, target_layout,
)

LENS_RECIPE_TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
           '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
LENS_RECIPE_COLORS = ('white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink',
          'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black')


class LensRecipeTargets(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()

    def source(self, target: str, path: str) -> Path:
        file = resolve_effective_source(target_layout(target, self.props), self.props, path)
        self.assertIsNotNone(file, f'{target} missing {path}')
        return file

    def recipe(self, target: str, file: str) -> dict:
        base = 'src/main/resources/data/buildcraftsilicon/'
        sub = 'recipes' if target.startswith(('1.19.', '1.20.')) else 'recipe'
        path = f'{base}{sub}/assembly/lens/{file}.json'
        return json.loads(self.source(target, path).read_text(encoding='utf-8'))

    def test_clear_lenses_require_uncoloured_glass_in_all_targets(self):
        for target in LENS_RECIPE_TARGETS:
            for kind, variant in (('lens_regular', 32), ('lens_filter', 33)):
                with self.subTest(target=target, kind=kind):
                    recipe = self.recipe(target, kind)
                    self.assertEqual(recipe['ingredients'][0], {'item': 'minecraft:glass'})
                    self.assertEqual(recipe['result']['nbt'], f'{{Damage:{variant}}}')
                    self.assertEqual(recipe['result']['item'], 'buildcraftsilicon:plug/lens')
                    self.assertEqual(recipe['group'], 'buildcraftsilicon:lens_filters' if kind == 'lens_filter' else 'buildcraftsilicon:lens_regulars')
                    self.assertEqual(recipe['ingredient_counts'], [1, 1] if kind == 'lens_filter' else [1])
                    if kind == 'lens_filter':
                        self.assertEqual(recipe['ingredients'][1], {'item': 'minecraft:iron_bars'})

    def test_all_sixteen_colours_and_both_lens_types(self):
        for target in LENS_RECIPE_TARGETS:
            for colour_id, colour in enumerate(LENS_RECIPE_COLORS):
                for kind, offset in (('regular', 0), ('filter', 16)):
                    with self.subTest(target=target, colour=colour, kind=kind):
                        recipe = self.recipe(target, f'{kind}/{colour}')
                        self.assertEqual(recipe['ingredients'][0], {'item': f'minecraft:{colour}_stained_glass'})
                        self.assertEqual(recipe['result']['nbt'], f'{{Damage:{colour_id + offset}}}')
                        self.assertEqual(recipe['result']['item'], 'buildcraftsilicon:plug/lens')
                        self.assertEqual(recipe['ingredient_counts'], [1, 1] if kind == 'filter' else [1])
                        if kind == 'filter':
                            self.assertEqual(recipe['ingredients'][1], {'item': 'minecraft:iron_bars'})

    def test_datagen_agrees_with_packaged_recipes(self):
        path = 'src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java'
        for target in LENS_RECIPE_TARGETS:
            with self.subTest(target=target):
                source = self.source(target, path).read_text(encoding='utf-8')
                self.assertIn('IngredientStack glass = IngredientStack.of(Blocks.GLASS);', source)
                self.assertIn('group("buildcraftsilicon:lens_filters")', source)

    def test_modern_jei_identifies_lens_variant_by_custom_data(self):
        path = 'src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java'
        for target in LENS_RECIPE_TARGETS:
            with self.subTest(target=target):
                source = self.source(target, path).read_text(encoding='utf-8')
                if target.startswith(('1.19.', '1.20.')):
                    self.assertNotIn('ItemPluggableLens.getData(stack).getItemDamage()', source)
                else:
                    self.assertIn('ItemPluggableLens.getData(stack).getItemDamage()', source)
                    self.assertIn('ItemPluggableLens.getData(', source)
                    self.assertNotIn('Integer.toString(stack.getDamageValue())', source)
                    self.assertNotIn('case LENS, LENS_FILTER -> Comparator.comparingInt(view -> assemblyResult(view.recipe()).getDamageValue())', source)

    def test_26x_template_preserves_legacy_variant_for_non_damageable_item(self):
        path = 'src/main/java/buildcraft/lib/recipe/AssemblyRecipe.java'
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                layout = target_layout(target, self.props)
                selected_source = self.source(target, path)
                source, _ = strip_source_condition(selected_source.read_text(encoding='utf-8'))
                # Resolve Stonecutter branches before extracting a Java method. Both
                # variants include a closing brace; raw brace counting used to cut
                # the method at the inactive branch and generated invalid Java.
                source = preprocess_text(
                    source,
                    minecraft=self.props[f'target.{target}.deps.minecraft'],
                    family=layout.family,
                    platform=layout.platform,
                    source=f'{target}:{path}',
                )
                signature = '        private ItemStackTemplate template() {'
                start = source.index(signature)
                opening = source.index('{', start)
                depth = 1
                cursor = opening + 1
                while depth:
                    depth += (source[cursor] == '{') - (source[cursor] == '}')
                    cursor += 1
                method = source[start:cursor]
                self.assertIn('new ItemStack(item).isDamageableItem()', method)
                # Execute the *actual Java implementation* using minimal data-component and NBT stubs.
                fixture = '''import java.util.*;
public class LensCodecProbe {
    static class Item { final boolean damageable; Item(boolean b){damageable=b;} }
    static class ItemStack { final Item item; ItemStack(Item item){this.item=item;} boolean isDamageableItem(){return item.damageable;} }
    static class CompoundTag {
        final Map<String,Integer> values = new HashMap<>();
        boolean isEmpty(){return values.isEmpty();}
        void remove(String key){values.remove(key);}
        int getInt(String key){return values.getOrDefault(key,0);}
    }
    static class NbtCompat {
        static boolean contains(CompoundTag tag,String key,int type){return tag.values.containsKey(key);}
        static int getInt(CompoundTag tag,String key){return tag.getInt(key);}
    }
    static class DataComponents {static final String DAMAGE="durability"; static final String CUSTOM_DATA="custom";}
    static class CustomData {static CompoundTag of(CompoundTag tag){return tag;}}
    static class DataComponentPatch {
        static Builder builder(){return new Builder();}
        static class Builder {
            Map<String,Object> components = new HashMap<>();
            void set(String key,Object value){components.put(key,value);}
            Map<String,Object> build(){return components;}
        }
    }
    static class ItemStackTemplate {
        final Item item; final int count; final Map<String,Object> components;
        ItemStackTemplate(Item item,int count,Map<String,Object> components){this.item=item;this.count=count;this.components=components;}
    }
    static class LegacyResult {
        final Item item;final int count;final int damage;
        LegacyResult(Item item,int damage){this.item=item;this.count=1;this.damage=damage;}
        CompoundTag parseLegacyTag(){CompoundTag tag=new CompoundTag();tag.values.put("Damage",damage);tag.values.put("Other",42);return tag;}
METHOD
    }
    public static void main(String[] args) {
        for (int variant=0;variant<34;variant++) {
            ItemStackTemplate lens = new LegacyResult(new Item(false),variant).template();
            if (lens.components.containsKey(DataComponents.DAMAGE))throw new AssertionError("lens durability "+variant);
            CompoundTag tag=(CompoundTag)lens.components.get(DataComponents.CUSTOM_DATA);
            if(tag==null||tag.getInt("Damage")!=variant||tag.getInt("Other")!=42)throw new AssertionError("lens variant lost: "+variant);
        }
        ItemStackTemplate tool = new LegacyResult(new Item(true),17).template();
        if(!Integer.valueOf(17).equals(tool.components.get(DataComponents.DAMAGE)))throw new AssertionError("tool durability lost");
        CompoundTag other=(CompoundTag)tool.components.get(DataComponents.CUSTOM_DATA);
        if(other==null||other.values.containsKey("Damage")||other.getInt("Other")!=42)throw new AssertionError("tool custom data broken");
        System.out.println("34 lens variants and tool durability PASS");
    }
}
'''.replace('METHOD', method)
                with tempfile.TemporaryDirectory(prefix='bcce-lens-codec-') as tmp:
                    work = Path(tmp)
                    (work / 'LensCodecProbe.java').write_text(fixture, encoding='utf-8')
                    cmd = subprocess.run(['javac', '--release', '21', 'LensCodecProbe.java'], cwd=work, capture_output=True, text=True, timeout=30)
                    self.assertEqual(cmd.returncode, 0, cmd.stderr)
                    run = subprocess.run(['java', 'LensCodecProbe'], cwd=work, capture_output=True, text=True, timeout=20)
                    self.assertEqual(run.returncode, 0, run.stderr)
                    self.assertIn('PASS', run.stdout)


if __name__ == '__main__':
    unittest.main()
