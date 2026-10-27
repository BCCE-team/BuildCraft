"""Coverage for Integration and Programming data-owned recipe extensions."""
from __future__ import annotations
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENERGY = {
    "picker": 800, "carrier": 800, "fluid_carrier": 800,
    "lumberjack": 3200, "harvester": 3200, "miner": 3200,
    "planter": 3200, "farmer": 3200, "leave_cutter": 3200,
    "butcher": 3200, "shovelman": 3200, "pump": 3200,
    "delivery": 12800, "knight": 12800, "bomber": 12800,
    "stripes": 12800, "builder": 51200,
}


class RecipePhaseThreeTest(unittest.TestCase):
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
                dynamic = json.loads((base / "integration_table/robot_board_integration.json").read_text())
                self.assertEqual(dynamic["type"], "buildcraftsilicon:robot_board_integration")
                self.assertEqual(dynamic["energy"]["mj"], 5000)

    def test_no_hardcoded_builtin_registration(self):
        for family in ("old", "1.21.X"):
            source = (ROOT / f"source-families/{family}/src/main/java/buildcraft/robotics/recipes/RobotIntegrationRecipe.java").read_text()
            self.assertIn("MachineRecipeDatapackReloadListener.registerHandler", source)
            self.assertNotIn("service.register(ID", source)
            self.assertIn("powerMicroMj", source)
            self.assertIn("integration_table/robot_board_integration", source)

    def test_json_reader_and_serializers_all_targets(self):
        loader = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text()
        self.assertIn('"integration_table", "programming_table"', loader)
        self.assertIn('"buildcraftsilicon:integration"', loader)
        self.assertIn('"buildcraftsilicon:programming"', loader)
        self.assertIn('HANDLERS.get(type)', loader)
        self.assertIn("Unregistered board key", loader)
        self.assertNotIn("Two programming recipes target board key", loader)
        self.assertIn("Duplicate programming recipe content at", loader)
        for folder in ("version-src/1.19.2-forge", "version-src/1.20.1-forge", "source-families/1.21.X", "source-families/26.X"):
            source = (ROOT / folder / "src/main/java/buildcraft/silicon/BCSiliconRecipes.java").read_text()
            with self.subTest(folder=folder):
                self.assertIn('SERIALIZERS.register("programming"', source)
                self.assertIn('SERIALIZERS.register("integration"', source)
                self.assertIn('SERIALIZERS.register("robot_board_integration"', source)

    def test_machine_runtime_and_client_are_data_owned(self):
        for folder in ("source-families/1.21.X", "source-platforms/forge"):
            table = (ROOT / folder / "src/main/java/buildcraft/silicon/tile/TileProgrammingTable_Neptune.java").read_text()
            integration = (ROOT / folder / "src/main/java/buildcraft/silicon/tile/TileIntegrationTable.java").read_text()
            with self.subTest(folder=folder):
                self.assertIn("ProgrammingRecipeAccess.options(", table)
                self.assertIn("lastRecipeGeneration", table)
                self.assertIn("syncedProgrammingOptions", table)
                self.assertIn("buffer.writeLong(recipe.requiredMicroJoules())", table)
                self.assertIn("optionDefinitions.get(absolute).requiredMicroJoules()", table)
                self.assertIn("AssemblyIngredients.remaining(items, supplies)", integration)
                self.assertNotIn("selected.energyCost() *", table)
        client = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/ClientMachineRecipeCatalog.java").read_text()
        self.assertIn("visibleDefinition", client)
        for file in ROOT.rglob("BuildCraftJeiPlugin.java"):
            source = file.read_text()
            self.assertIn("ProgrammingRecipeAccess.options(true)", source)
            self.assertIn("integration_table/robot_board_integration", source)
            self.assertIn("for (BoardEntry board : BCRoboticsBoards.robotEntries())", source)
            self.assertNotIn('"buildcraftrobotics:robot_board_integration"', source)


if __name__ == "__main__":
    unittest.main()
