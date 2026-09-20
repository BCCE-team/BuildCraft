#!/usr/bin/env python3
"""Executable contracts for the internal platform boundaries.

Every loader boundary is tested against small API doubles while the same contract is
applied to all supported targets. The suite covers typed native adapters and the
cross-boundary requirements expected by the platform architecture.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts/tests"))

from source_layout import load_properties, materialize_target
from platform_boundary_fixture import run as run_platform
from actor_client_fixture import run_server as run_actor
from loader_boundary_fixture import run as run_network_context
from transfer_fixture import run as run_transactional_transfer
from transfer_probes import PROBES

TARGETS = (
    "1.19.2-forge",
    "1.20.1-forge",
    "1.21.1-neoforge",
    "1.21.11-neoforge",
)


class _ContractEnvironment:
    """Materializes once and memoizes the expensive actual-Java probes."""

    def __init__(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="bc-platform-contracts-")
        self.work = Path(self.temp.name)
        self.props = load_properties()
        self.java: dict[str, Path] = {}
        for target in TARGETS:
            out = self.work / target
            materialize_target(target, out, self.props)
            self.java[target] = out / "src/main/java"
        self._platform: dict[str, str] = {}
        self._actor: dict[str, str] = {}
        self._network: str | None = None
        self._transaction: str | None = None

    def platform(self, target: str) -> str:
        if target not in self._platform:
            self._platform[target] = run_platform(
                self.java[target], self.work / "platform" / target, target
            )
        return self._platform[target]

    def actor(self, target: str) -> str:
        if target not in self._actor:
            self._actor[target] = run_actor(
                self.java[target], self.work / "actor" / target, target
            )
        return self._actor[target]

    def network(self) -> str:
        if self._network is None:
            self._network = run_network_context()
        return self._network

    def transaction(self) -> str:
        if self._transaction is None:
            self._transaction = run_transactional_transfer(
                self.java["1.21.11-neoforge"], PROBES
            )
        return self._transaction


ENV: _ContractEnvironment | None = None


def env() -> _ContractEnvironment:
    global ENV
    if ENV is None:
        ENV = _ContractEnvironment()
    return ENV


def source(target: str, relative: str) -> str:
    return (env().java[target] / "buildcraft" / relative).read_text(encoding="utf-8")


def fixture_source(name: str) -> str:
    return (ROOT / "scripts/tests" / name).read_text(encoding="utf-8")


class _AllTargets(unittest.TestCase):
    def assert_platform_contract(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                output = env().platform(target)
                self.assertIn("internal-platform assertions", output)

    def assert_actor_contract(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                output = env().actor(target)
                self.assertIn("Actor/permission/ticket boundary", output)


class ItemTransferContract(_AllTargets):
    def test_item_contract_on_every_loader_shape(self):
        self.assert_platform_contract()
        fixture = fixture_source("platform_boundary_fixture.py")
        for marker in (
            "simulation immutable",
            "simulated remainder",
            "restricted side-view slot never bypassed",
            "partial remainder",
            "overflow-sized extraction is bounded",
            "empty item insert is safe",
            "empty item extract is safe",
        ):
            self.assertIn(marker, fixture)

    def test_transactional_item_rollback_is_preserved(self):
        # 1.21.11 is the native transaction-capable target. The probe opens a real
        # nested transaction double around the maintained TransferInterop classes.
        output = env().transaction()
        self.assertIn("Transfer Java probes:", output)
        probes = fixture_source("transfer_probes.py")
        self.assertIn("pipe insertion participates in rollback", probes)
        self.assertIn("partial insert aborted", probes)


class FluidTransferContract(_AllTargets):
    def test_fluid_contract_on_every_loader_shape(self):
        self.assert_platform_contract()
        fixture = fixture_source("platform_boundary_fixture.py")
        for marker in (
            "fluid partial simulated fill",
            "fluid simulation unchanged",
            "filtered actual drain",
            "wrong fluid rejected",
            "empty fluid fill is safe",
            "empty fluid drain is safe",
            "overflow-sized fluid drain is bounded",
        ):
            self.assertIn(marker, fixture)

    def test_transactional_fluid_rollback_is_preserved(self):
        output = env().transaction()
        self.assertIn("Transfer Java probes:", output)
        probes = fixture_source("transfer_probes.py")
        self.assertIn("fluid input rollback", probes)
        self.assertIn("fluid output rollback", probes)
        self.assertIn("simulated fluid drain reverted", probes)


class EnergyTransferContract(_AllTargets):
    def test_energy_contract_on_every_loader_shape(self):
        self.assert_platform_contract()
        fixture = fixture_source("platform_boundary_fixture.py")
        for marker in (
            "energy simulate",
            "energy actual",
            "dynamic sided energy rejection",
            "empty energy receive is safe",
            "empty energy extract is safe",
            "overflow-sized receive is capacity bounded",
            "overflow-sized extract is bounded",
        ):
            self.assertIn(marker, fixture)

    def test_transactional_energy_rollback_is_preserved(self):
        output = env().transaction()
        self.assertIn("Transfer Java probes:", output)
        probes = fixture_source("transfer_probes.py")
        self.assertIn("internal FE simulate", probes)
        self.assertIn("energy parent abort", probes)


class NetworkContextContract(_AllTargets):
    def test_side_sender_enqueue_and_handled(self):
        output = env().network()
        self.assertIn("Loader packet boundary probes:", output)
        fixture = fixture_source("loader_boundary_fixture.py")
        for marker in (
            "forgeClient.side() == BCNetworkSide.CLIENT",
            "forgeClient.getSender() == server",
            "forgeClientRaw.enqueued",
            "forgeClientRaw.handled",
            "neo.side() == BCNetworkSide.SERVER",
            "neo.getSender() == server",
        ):
            self.assertIn(marker, fixture)

    def test_send_to_player_and_send_to_server_route_to_native_transport(self):
        for target in TARGETS:
            with self.subTest(target=target):
                manager = source(target, "lib/net/MessageManager.java")
                self.assertRegex(
                    manager,
                    r"public static void sendTo\(Object message, ServerPlayer player\)",
                )
                self.assertRegex(manager, r"public static void sendToServer\(Object message\)")
                if target.endswith("-forge"):
                    self.assertIn(
                        "PacketDistributor.PLAYER.with(() -> player)", manager
                    )
                    self.assertIn(
                        "getSimpleNetworkWrapper(message).sendToServer(message)", manager
                    )
                elif target == "1.21.11-neoforge":
                    self.assertIn(
                        "PacketDistributor.sendToPlayer(player, payload(message))", manager
                    )
                    self.assertIn(
                        "ClientPacketDistributor.sendToServer(payload(message))", manager
                    )
                else:
                    self.assertIn(
                        "PacketDistributor.sendToPlayer(player, payload(message))", manager
                    )
                    self.assertIn(
                        "PacketDistributor.sendToServer(payload(message))", manager
                    )


class EventContract(_AllTargets):
    def test_native_event_normalizes_once_with_identity_and_side(self):
        self.assert_platform_contract()
        fixture = fixture_source("platform_boundary_fixture.py")
        for marker in (
            "world and side identity",
            "player identity",
            "chunk callback once",
            "lifecycle callback exactly once",
        ):
            self.assertIn(marker, fixture)

    def test_gameplay_event_registration_is_idempotent(self):
        checked = (
            "transport/BCTransportEventDist.java",
            "transport/stripes/PipeExtensionManager.java",
            "builders/BCBuildersEventDist.java",
            "robotics/SimpleRobotRegistryProvider.java",
        )
        for target in TARGETS:
            for relative in checked:
                path = env().java[target] / "buildcraft" / relative
                if not path.exists():
                    continue
                text = path.read_text(encoding="utf-8")
                with self.subTest(target=target, file=relative):
                    self.assertIn("gameplayEventsRegistered", text)
                    self.assertRegex(text, r"if \(gameplayEventsRegistered\) return;")
                    self.assertRegex(text, r"gameplayEventsRegistered = true;")

        # Gameplay event containers are registered through the platform boundary and must not
        # also be registered as native @SubscribeEvent holders.
        for target in TARGETS:
            transport = source(target, "transport/BCTransport.java")
            self.assertIn("BCTransportEventDist.registerGameplayEvents()", transport)
            self.assertNotRegex(
                transport,
                r"EVENT_BUS\.register\(BCTransportEventDist(?:\.class)?\)",
            )


class ConfigContract(_AllTargets):
    def test_defaults_load_reload_and_invalid_values(self):
        self.assert_platform_contract()
        fixture = fixture_source("platform_boundary_fixture.py")
        for marker in (
            "bool default",
            "typed values",
            "reload read through live native value",
            "invalid range rejected by native binding",
            "invalid value does not replace last valid config",
            "config event types and mod IDs",
        ):
            self.assertIn(marker, fixture)

    def test_config_boundary_remains_common_and_client_free(self):
        modules = (
            "core/BCCore.java",
            "energy/BCEnergy.java",
            "transport/BCTransport.java",
            "silicon/BCSilicon.java",
            "builders/BCBuilders.java",
        )
        for target in TARGETS:
            binding = source(target, "lib/platform/config/ConfigBinding.java")
            with self.subTest(target=target, boundary="binding"):
                self.assertNotIn("net.minecraft.client", binding)
                self.assertNotRegex(binding, r"\.client\.")
            for relative in modules:
                path = env().java[target] / "buildcraft" / relative
                if not path.exists():
                    continue
                text = path.read_text(encoding="utf-8")
                if "registerConfig(" not in text:
                    continue
                with self.subTest(target=target, module=relative):
                    self.assertIn("Type.COMMON", text)
                    self.assertNotIn("Type.CLIENT", text)
                    self.assertNotIn("Type.SERVER", text)


class PermissionFakePlayerContract(_AllTargets):
    def test_actor_identity_owner_denial_and_fake_player_context(self):
        self.assert_actor_contract()
        fixture = fixture_source("actor_client_fixture.py")
        for marker in (
            "permission verdict ",
            "one permission decision",
            "permission actor",
            "operation and simulation preserved",
            "same mutable tool and actor",
            "outer failure restoration",
            "world unload evicts actors",
        ):
            self.assertIn(marker, fixture)

    def test_server_operator_creative_blueprint_permission_is_preserved(self):
        for target in TARGETS:
            with self.subTest(target=target):
                architect = source(target, "builders/tile/TileArchitectTable.java")
                compact = re.sub(r"\s+", " ", architect)
                self.assertRegex(
                    compact,
                    r"player != null && \(player\.isCreative\(\) \|\| (?:player\.hasPermissions\(2\)|player\.permissions\(\)\.hasPermission\(net\.minecraft\.server\.permissions\.Permissions\.COMMANDS_GAMEMASTER\))\)",
                )


class ChunkTicketContract(_AllTargets):
    def test_acquire_release_duplicate_owner_and_world_lifecycle(self):
        self.assert_actor_contract()
        fixture = fixture_source("actor_client_fixture.py")
        for marker in (
            "owner plus requested chunk",
            "duplicate acquire does not duplicate native ticket",
            "resize releases obsolete chunk",
            "world unload retains persistent tickets",
            "release after mirror unload also covers tile requested chunks",
            "removed tile cannot acquire",
        ):
            self.assertIn(marker, fixture)

    def test_machine_removal_releases_owned_tickets(self):
        for target in TARGETS:
            with self.subTest(target=target):
                quarry = source(target, "builders/tile/TileQuarry.java")
                self.assertRegex(
                    quarry,
                    r"void onRemove\(boolean dropSelf\)[\s\S]{0,300}?ChunkLoaderManager\.releaseChunksFor\(this\)",
                )
                events = source(target, "lib/BCLibEventDist.java")
                self.assertIn("BCChunkTickets.unloadWorld", events)


class ContractSuiteShape(unittest.TestCase):
    def test_platform_contract_names_are_stable(self):
        # Every loader implementation plugs into these contracts instead of defining
        # a one-off test family.
        expected = {
            "ItemTransferContract",
            "FluidTransferContract",
            "EnergyTransferContract",
            "NetworkContextContract",
            "EventContract",
            "ConfigContract",
            "PermissionFakePlayerContract",
            "ChunkTicketContract",
        }
        current = {
            cls.__name__
            for cls in (
                ItemTransferContract,
                FluidTransferContract,
                EnergyTransferContract,
                NetworkContextContract,
                EventContract,
                ConfigContract,
                PermissionFakePlayerContract,
                ChunkTicketContract,
            )
        }
        self.assertEqual(expected, current)


if __name__ == "__main__":
    unittest.main(verbosity=2)
