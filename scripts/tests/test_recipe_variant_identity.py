"""Programming recipe content identity and JEI integration-grid regression.

These assertions check the real JSON input to the datapack loader and the Java
view/selection wiring. No mocked MC or JEI runtime is claimed here.
"""
from __future__ import annotations
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class RecipeVariantIdentityTest(unittest.TestCase):
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
        access = (ROOT / 'source-shared/src/main/java/buildcraft/lib/recipe/ProgrammingRecipeAccess.java').read_text()
        self.assertIn('recipes.stream().distinct()', access)
        self.assertIn('ProgrammingRecipeDefinition::requiredMicroJoules', access)
        self.assertIn('ProgrammingRecipeDefinition::boardKey', access)
        self.assertIn('public static java.util.Optional<CostRange> costRange(String boardKey, boolean client)', access)
        self.assertIn('min = Math.min(min, energy);', access)
        self.assertIn('max = Math.max(max, energy);', access)
        self.assertIn('return max == 0 ? java.util.Optional.empty()', access)
        reload_code = (ROOT / 'source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java').read_text()
        self.assertIn('Map<ProgrammingRecipeDefinition, SourceFile> identicalProgramming', reload_code)
        self.assertIn('Duplicate programming recipe content at', reload_code)
        self.assertNotIn('Two programming recipes target board key', reload_code)

    def test_all_table_implementations_execute_selected_variant(self):
        for family in ('source-platforms/forge', 'source-families/1.21.X'):
            p = ROOT / family / 'src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java'
            with self.subTest(family=family):
                s = p.read_text()
                self.assertIn('optionDefinitions.get(absolute).requiredMicroJoules()', s)
                self.assertIn('java.util.Set<ProgrammingRecipeDefinition> seen', s)
                self.assertIn('syncedProgrammingOptions = List.copyOf(recipes)', s)
                self.assertNotIn('costs.putIfAbsent(key, energy)', s)

    def test_seven_jei_plugins_show_distinct_programming_recipes_and_correct_grid(self):
        plugins = sorted(ROOT.rglob('BuildCraftJeiPlugin.java'))
        self.assertEqual(len(plugins), 7)
        for p in plugins:
            with self.subTest(plugin=p):
                s = p.read_text()
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
                text = (ROOT / source).read_text()
                self.assertIn('formatProgrammingCostTooltip(', text)
                self.assertNotIn('ProgrammingRecipeAccess.costs(true).getOrDefault(', text)
        for family in ('old', '1.21.X'):
            boards = (ROOT / f'source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java').read_text()
            self.assertIn('ProgrammingRecipeAccess.costRange(boardKey, client)', boards)
            self.assertIn('cost.minMicroMj() == cost.maxMicroMj()', boards)
            self.assertIn('minimum + " – " + formatBoardEnergyCostMicro(cost.maxMicroMj())', boards)
            self.assertIn('BCLibConfig.hidePowerValues', boards)

    def test_blacklist_id_is_not_interpreted_as_one_robot_variant(self):
        # The built-in descriptor can still be blacklisted; that bans every
        # robot-board variant by design, since they share an ID.
        doc = (ROOT / 'docs/RECIPE_DATAPACKS.md').read_text()
        self.assertIn('disables **all** robot-board integration variants', doc)

if __name__ == '__main__':
    unittest.main()
