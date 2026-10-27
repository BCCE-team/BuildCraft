"""JEI recipe recovery, saved MJ escrow and client/server lifecycle safeguards.

The tests check source wiring for all supported target trees and the refund
accounting invariant. The latter is a deliberately independent reference model;
it is not a substitute for Minecraft GameTests.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RecipeRuntimeRecoveryTest(unittest.TestCase):
    def test_secondary_integration_grid_is_shifted_one_pixel(self):
        plugins = sorted(ROOT.rglob("BuildCraftJeiPlugin.java"))
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
        source = (ROOT / "source-shared/src/main/java/buildcraft/lib/net/MessageMachineRecipes.java").read_text()
        self.assertIn("if (!MessageManager.isAuthoritativeServerThread()) return;", source)
        managers = sorted(ROOT.rglob("buildcraft/lib/net/MessageManager.java"))
        self.assertEqual(len(managers), 5)
        for manager in managers:
            code = manager.read_text()
            self.assertIn("boolean isAuthoritativeServerThread()", code)
            self.assertIn("server != null && server.isSameThread()", code)

    def test_vanilla_neoforge_mirrors_not_logged_as_190_conflicts(self):
        source = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text()
        self.assertIn("isBuiltinNeoForgeOverlay(fileId, entry.getValue())", source)
        self.assertIn('packs.equals(Set.of("vanilla", "mod/neoforge"))', source)
        self.assertIn('warnings.add("Duplicate recipe ID "', source)


if __name__ == "__main__":
    unittest.main()
