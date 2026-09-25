from __future__ import annotations

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
FABRIC = ROOT / "source-platforms/fabric/src/main/java"


class FabricServerFoundationTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_bootstrap_installs_loader_neutral_boundaries(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/fabric/BuildCraftFabric.java")
        for token in (
            "PlatformRuntime.install(FabricRuntimePlatform.INSTANCE)",
            "PlatformApi2Bootstrap.install()",
            "MjApi2PlatformBridge.install()",
            "FabricNetworkManager.installServerReceivers()",
            "ServerLifecycleEvents.SERVER_STARTED",
            "ServerLifecycleEvents.SERVER_STOPPED",
        ):
            self.assertIn(token, text)

    def test_network_transport_has_all_common_send_paths(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/net/FabricNetworkManager.java")
        for method in (
            "sendToAll(Object message)",
            "sendToPlayer(Object message, ServerPlayer player)",
            "sendToServer(Object message)",
            "sendToTrackingChunk(Object message, LevelChunk chunk)",
            "sendToDimension(Object message, ResourceKey<Level> dimension)",
        ):
            self.assertIn(method, text)
        self.assertIn("if (serverReceiversInstalled && direction != BCMessageDirection.CLIENTBOUND)", text)
        self.assertIn("if (clientBridge != ClientBridge.UNAVAILABLE)", text)
        self.assertNotIn("net.minecraftforge", text)
        self.assertNotIn("net.neoforged", text)

    def test_transfer_bridges_cover_item_fluid_and_energy(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        self.assertIn("ItemStorage.SIDED.find", text)
        self.assertIn("FluidStorage.SIDED.find", text)
        self.assertIn("team.reborn.energy.api.EnergyStorage.SIDED.find", text)
        self.assertIn("Transaction.openOuter()", text)

    def test_fabric_platform_does_not_fork_gameplay(self) -> None:
        forbidden_prefixes = ("Tile", "PipeFlow", "PipeBehaviour", "Robot", "BoardRobot", "EntityRobot")
        offenders = []
        for path in FABRIC.rglob("*.java"):
            if path.name.startswith(forbidden_prefixes):
                offenders.append(path.relative_to(ROOT).as_posix())
        self.assertEqual([], offenders)

    def test_server_foundation_metadata_does_not_claim_gameplay_modules(self) -> None:
        text = self.read("source-platforms/fabric/src/main/resources/fabric.mod.json")
        self.assertIn('"buildcraft:server_foundation": true', text)
        self.assertNotIn('"buildcraft:skeleton"', text)
        self.assertNotIn('"provides"', text)


if __name__ == "__main__":
    unittest.main()
