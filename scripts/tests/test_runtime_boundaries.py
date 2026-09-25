from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class RuntimeBoundaryTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_module_identity_has_one_loader_neutral_owner(self) -> None:
        shared = ROOT / "source-shared/src/main/java/buildcraft/lib/internal/module/BCModules.java"
        self.assertTrue(shared.is_file())
        self.assertIn("PlatformRuntime.isModLoaded", shared.read_text(encoding="utf-8"))
        self.assertFalse((ROOT / "source-platforms/forge/src/main/java/buildcraft/lib/internal/module/BCModules.java").exists())
        self.assertFalse((ROOT / "source-platforms/neoforge/src/main/java/buildcraft/lib/internal/module/BCModules.java").exists())

    def test_loader_bootstraps_install_runtime_platform_before_common_services(self) -> None:
        variants = {
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/lib/BCLib.java": "ForgeRuntimePlatform.INSTANCE",
            "source-downports/legacy/1.19.2/forge/src/main/java/buildcraft/lib/BCLib.java": "ForgeRuntimePlatform.INSTANCE",
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/lib/BCLib.java": "NeoForgeRuntimePlatform.INSTANCE",
        }
        for relative, implementation in variants.items():
            with self.subTest(relative=relative):
                text = self.read(relative)
                install = text.index(f"PlatformRuntime.install({implementation})")
                api2 = text.index("PlatformApi2Bootstrap.install()")
                self.assertLess(install, api2)
        self.assertTrue((ROOT / "source-family-platforms/legacy/forge/src/main/java/buildcraft/lib/platform/runtime/ForgeRuntimePlatform.java").is_file())
        self.assertTrue((ROOT / "source-family-platforms/modern/neoforge/src/main/java/buildcraft/lib/platform/runtime/NeoForgeRuntimePlatform.java").is_file())

    def test_common_and_family_gameplay_sends_through_bcnetwork(self) -> None:
        roots = [ROOT / "source-shared", ROOT / "source-families"]
        offenders: list[str] = []
        for root in roots:
            for path in root.rglob("*.java"):
                text = path.read_text(encoding="utf-8", errors="ignore")
                if "MessageManager.sendTo" in text:
                    offenders.append(path.relative_to(ROOT).as_posix())
        self.assertEqual([], offenders)

    def test_legacy_packet_catalog_owns_direction_and_codec_declarations(self) -> None:
        catalog = self.read("source-families/legacy/src/main/java/buildcraft/lib/net/LegacyNetworkCatalog.java")
        expected = {
            "MessageUpdateTile",
            "MessageContainer",
            "MessageMarker",
            "MessageObjectCacheRequest",
            "MessageObjectCacheResponse",
            "MessageDebugRequest",
            "MessageDebugResponse",
            "MessageGuideState",
            "MessageVolumeBoxes",
            "MessageSnapshotRequest",
            "MessageSnapshotResponse",
            "MessageZoneMapRequest",
            "MessageZoneMapResponse",
            "MessageWireSystems",
            "MessageWireSystemsPowered",
            "MessageMultiPipeItem",
        }
        actual = set(re.findall(r"registrar\.register\(\s*BCModules\.[A-Z_]+,\s*([A-Za-z0-9_]+)\.class", catalog))
        self.assertEqual(expected, actual)
        self.assertIn("BCMessageDirection.CLIENTBOUND", catalog)
        self.assertIn("BCMessageDirection.SERVERBOUND", catalog)
        self.assertIn("BCMessageDirection.BIDIRECTIONAL", catalog)

    def test_legacy_forge_modules_delegate_packet_registration_to_catalog(self) -> None:
        variants = {
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/builders/BCBuilders.java": "registerBuilders",
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/robotics/BCRobotics.java": "registerRobotics",
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/transport/BCTransport.java": "registerTransport",
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/core/BCCore.java": "registerCore",
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/lib/BCLib.java": "registerLibrary",
        }
        for relative, method in variants.items():
            with self.subTest(relative=relative):
                text = self.read(relative)
                self.assertIn(f"LegacyNetworkCatalog.{method}(MessageManager::registerCatalogMessage)", text)

    def test_legacy_tile_base_has_persistence_hooks(self) -> None:
        text = self.read("source-platforms/forge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java")
        self.assertIn("protected void readData(BCValueInput input)", text)
        self.assertIn("protected void writeData(BCValueOutput output)", text)
        self.assertIn("readData(input);", text)
        self.assertIn("writeData(output);", text)
        self.assertTrue((ROOT / "source-families/legacy/src/main/java/buildcraft/lib/compat/minecraft/persistence/BCValueInput.java").is_file())
        self.assertTrue((ROOT / "source-families/legacy/src/main/java/buildcraft/lib/compat/minecraft/persistence/BCValueOutput.java").is_file())

    def test_tile_lifecycle_distinguishes_unload_invalidation_and_destruction(self) -> None:
        lifecycle = self.read("source-shared/src/main/java/buildcraft/lib/lifecycle/BCBlockEntityLifecycle.java")
        for token in ("bcOnLoad", "bcOnChunkUnload", "bcOnInvalidated", "bcOnRevived", "bcOnDestroyed"):
            self.assertIn(token, lifecycle)
        for relative in (
            "source-platforms/forge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java",
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java",
            "source-downports/modern/1.21.1/neoforge/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java",
        ):
            with self.subTest(relative=relative):
                text = self.read(relative)
                self.assertIn("bcOnChunkUnload();", text)
                self.assertIn("bcOnInvalidated();", text)
                self.assertIn("bcOnDestroyed(dropSelf);", text)


if __name__ == "__main__":
    unittest.main()
