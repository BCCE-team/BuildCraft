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
            self.assertIn("options = new ArrayList<>(BCRoboticsBoards.robotEntries())", tile, target)
            self.assertIn("options.sort", tile, target)
            self.assertIn("if (option < 0 || option >= opts.size())", tile, target)
            self.assertIn("selectedOption = -1;", tile, target)
            self.assertIn("sendNetworkGuiUpdate(NET_GUI_DATA);", tile, target)

            self.assertIn("new SlotDisplay(options, index", menu, target)
            self.assertIn("sendSelectOption", menu, target)
            self.assertIn("side == BCNetworkSide.SERVER", menu, target)
            self.assertIn("tile.selectOption(buffer.readVarInt())", menu, target)
            self.assertIn("container.sendSelectOption(container.tile.selectedOption == i ? -1 : i)", gui, target)

    def test_laser_targets_only_tables_with_real_work(self):
        for target in self.roots:
            laser = self.java(target, "buildcraft/silicon/tile/TileLaser.java")
            base = self.java(target, "buildcraft/silicon/tile/TileLaserTableBase.java")
            self.assertIn("laserTargets().target(level, p, laserTargetSide()).isPresent()", laser, target)
            self.assertIn("insert(MjAmount.ofMicro(getMaxPowerPerTick()), OperationMode.SIMULATE)", laser, target)
            self.assertIn("targetsNeedingPower.add(position)", laser, target)
            self.assertIn("level != null && level.isClientSide", base, target)
            self.assertIn("? targetClient : getTarget()", base, target)

    def test_12111_phantom_recipe_book_keeps_reference_features(self):
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
        for screen in (auto, advanced):
            self.assertIn('new ImageButton(', screen)
            self.assertIn('RecipeBookComponent.RECIPE_BUTTON_SPRITES', screen)
            self.assertNotIn('Component.literal("R")', screen)

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

    def test_12111_recipe_sync_still_covers_assembly_definitions(self):
        sync = self.java("1.21.11-neoforge", "buildcraft/silicon/BCSiliconRecipeSync.java")
        self.assertIn("event.sendRecipes(BCSiliconRecipes.ASSEMBLY_TYPE.get())", sync)
        self.assertIn("event.getRecipeMap().byType(BCSiliconRecipes.ASSEMBLY_TYPE.get())", sync)
        self.assertIn("assembly = List.copyOf", sync)
        self.assertIn("assembly = List.of();", sync)


if __name__ == "__main__":
    unittest.main()
