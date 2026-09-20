#!/usr/bin/env python3
"""Owner persistence, fake-player, ledger, and copy/place regression tests.

These are source/materialization contracts for both modern targets. They complement the
existing NeoForge GameTests, which exercise owner round-trips and the no-ACL rule in a
real game runtime when Gradle/GameTest infrastructure is available.
"""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_layout import load_properties, materialize_target

TARGETS = ("1.21.1-neoforge", "1.21.11-neoforge")


class OwnershipLedgers(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp = tempfile.TemporaryDirectory(prefix="bc-ownership-ledger-")
        cls.props = load_properties()
        cls.roots: dict[str, Path] = {}
        for target in TARGETS:
            out = Path(cls.temp.name) / target
            materialize_target(target, out, cls.props)
            cls.roots[target] = out

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def java(self, target: str, logical: str) -> str:
        return (self.roots[target] / "src/main/java" / logical).read_text(encoding="utf-8")

    def test_machine_owner_reads_are_passive_and_server_writes_are_dirty(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                tile = self.java(target, "buildcraft/lib/tile/TileBC_Neptune.java")
                getter_start = tile.index("public GameProfile getOwner()")
                getter_end = tile.index("public PermissionUtil.PermissionBlock", getter_start)
                getter = tile[getter_start:getter_end]
                self.assertIn("return owner == null ? FakePlayerProvider.NULL_PROFILE : owner", getter)
                self.assertNotIn("owner = FakePlayerProvider.NULL_PROFILE", getter)

                placed_start = tile.index("public void onPlacedBy(")
                placed_end = tile.index("public void onPlayerOpen(", placed_start)
                placed = tile[placed_start:placed_end]
                opened_start = placed_end
                opened_end = tile.index("public void onPlayerClose(", opened_start)
                opened = tile[opened_start:opened_end]
                self.assertIn("isClientSide", placed)
                self.assertIn("isClientSide", opened)
                self.assertGreaterEqual(placed.count("setChanged();"), 2)
                self.assertGreaterEqual(opened.count("setChanged();"), 2)

                self.assertIn("public final void setOwnerProfile", tile)
                self.assertIn("owner = hasProfileIdentity(profile) ? profile : null", tile)
                self.assertIn("setChanged();", tile[tile.index("public final void setOwnerProfile"):tile.index("public PermissionUtil.PermissionBlock")])

    def test_machine_owner_save_reload_preserves_partial_and_unknown_profiles(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                tile = self.java(target, "buildcraft/lib/tile/TileBC_Neptune.java")
                self.assertIn('map(TileBC_Neptune::readGameProfile).orElse(null)', tile)
                self.assertIn("if (hasProfileIdentity(owner))", tile)
                self.assertIn('output.put("owner", writeGameProfile(owner))', tile)
                # Explicit unknown/system attribution must survive a save. Passive getOwner() no longer
                # writes this fallback, so persisting it cannot turn an unowned legacy tile into owned state.
                write_start = tile.index("protected void writeCommonData(")
                write_end = tile.index("protected void migrateOldNBT", write_start)
                self.assertNotIn("owner != FakePlayerProvider.NULL_PROFILE", tile[write_start:write_end])
                self.assertIn('id == null && (name == null || name.isBlank())', tile)

                msg = self.java(target, "buildcraft/lib/misc/MessageUtil.java")
                profile_start = msg.index("public static void writeGameProfile")
                profile_end = msg.index("/** Writes a full block state", profile_start)
                profile_io = msg[profile_start:profile_end]
                for marker in (
                    "boolean hasId = id != null",
                    "boolean hasName = name != null && !name.isBlank()",
                    "buffer.writeBoolean(hasId)",
                    "buffer.writeBoolean(hasName)",
                    "UUID id = buffer.readBoolean() ? buffer.readUUID() : null",
                    "String name = buffer.readBoolean() ? buffer.readUtf(256) : null",
                    "return new GameProfile(id, name)",
                ):
                    self.assertIn(marker, profile_io)

    def test_ledgers_never_claim_owner_and_relayout_after_late_sync(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                ledger = self.java(target, "buildcraft/lib/gui/ledger/LedgerOwnership.java")
                self.assertNotIn("tile.getOwner()", ledger)
                self.assertGreaterEqual(ledger.count("tile.getKnownOwner()"), 2)
                self.assertIn("private String lastOwnerText", ledger)
                self.assertIn("public void tick()", ledger)
                self.assertIn("calculateMaxSize();", ledger)
                self.assertIn("toString()", ledger)  # UUID-only owner still has a stable visible identity.

                sprite = self.java(target, "buildcraft/lib/misc/SpriteUtil.java")
                # Name-only/unknown profiles are valid persistence states but cannot be sent to the
                # skin manager as though they had a UUID.
                if target == "1.21.11-neoforge":
                    self.assertIn("profile.id() == null", sprite)
                else:
                    self.assertIn("profile.getId() == null", sprite)

    def test_blueprint_copy_does_not_clone_source_machine_owner(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                schematic = self.java(target, "buildcraft/builders/snapshot/SchematicBlockDefault.java")
                self.assertIn("tileEntity instanceof TileBC_Neptune", schematic)
                self.assertIn('tileNbt.remove("owner")', schematic)
                self.assertIn("actor != null && tileEntity instanceof TileBC_Neptune bcTile", schematic)
                self.assertIn("bcTile.setOwnerProfile(actor.getGameProfile())", schematic)

                builder = self.java(target, "buildcraft/builders/snapshot/BlueprintBuilder.java")
                self.assertIn("GameProfile owner = getAutomationOwner(robot)", builder)
                self.assertIn("Player actor = getAutomationPlayer(robot, blockPos)", builder)
                self.assertIn("schematicBlock.build(tile.getWorldBC(), blockPos, actor)", builder)
                self.assertIn("return tile.getOwner();", builder)
                self.assertIn("getFakePlayer(serverLevel, getAutomationOwner(robot), pos)", builder)

                single = self.java(target, "buildcraft/builders/item/ItemSchematicSingle.java")
                self.assertIn("schematicBlock.build(world, placePos, player)", single)

    def test_robot_owner_survives_reload_and_drives_fake_player_identity(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                robot = self.java(target, "buildcraft/robotics/entity/EntityRobot.java")
                self.assertIn("owner = isRealOwner(profile) ? profile : null", robot)
                self.assertIn("pipeStation.getPipe().getKnownOwner()", robot)
                self.assertIn("owner = stationOwner", robot)
                self.assertIn("isRealOwner(owner)", robot)
                self.assertIn('"owner"', robot)
                self.assertIn("readOwnerProfile", robot)
                self.assertIn("writeOwnerProfile", robot)
                self.assertIn("id == null && (name == null || name.isBlank())", robot)

                item_robot = self.java(target, "buildcraft/robotics/item/ItemRobot.java")
                self.assertIn("robot.setOwner(player.getGameProfile())", item_robot)

                support = self.java(target, "buildcraft/robotics/internal/api2/RobotAutomationSupport.java")
                self.assertIn("getOwnerProfile()", support)

                for ai in (
                    "AIRobotHarvest.java",
                    "AIRobotPlant.java",
                    "AIRobotStripesHandler.java",
                ):
                    source = self.java(target, f"buildcraft/robotics/ai/{ai}")
                    self.assertIn("RobotAutomationSupport.owner(robot)", source)
                    self.assertIn("FakePlayerProvider.INSTANCE.getFakePlayer", source)

                use_tool = self.java(target, "buildcraft/robotics/ai/AIRobotUseToolOnBlock.java")
                self.assertIn("entityRobot.getOwnerProfile()", use_tool)
                self.assertIn("FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, owner", use_tool)

    def test_owner_display_compat_paths_are_read_only(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                jade = self.java(target, "buildcraft/compat/jade/BuildCraftJadePlugin.java")
                self.assertIn("tile.getKnownOwner()", jade)
                self.assertNotIn("GameProfile owner = tile.getOwner()", jade)

                # Ownership remains attribution, not a theft ACL. Runtime GameTests also enforce
                # this for machines and robot dismantling.
                gametest = (ROOT / "source-platforms/neoforge/src/gametest/java/buildcraft/gametest/PermissionOwnerGameTests.java").read_text(encoding="utf-8")
                self.assertIn("manualInteractionIsNotOwnerLocked", gametest)
                self.assertIn("robotDismantleIsNotOwnerLocked", gametest)
                self.assertIn("machineOwnerIdentitySurvivesPersistenceRoundTrip", gametest)
                self.assertIn("blueprintCopyUsesPlacementActorInsteadOfSourceMachineOwner", gametest)
                self.assertIn("schematic.build(level, targetPos, actor)", gametest)
                self.assertIn("robotOwnerIdentitySurvivesPersistenceAndFeedsApi2Actor", gametest)


if __name__ == "__main__":
    unittest.main(verbosity=2)
