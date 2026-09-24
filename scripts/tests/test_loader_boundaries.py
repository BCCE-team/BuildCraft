#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import unittest

from loader_boundary_fixture import run

ROOT = Path(__file__).resolve().parents[2]


class LoaderBoundaries(unittest.TestCase):
    def test_real_packet_context_adapters_against_offline_contracts(self):
        print(run())

    def test_raw_packet_contexts_stop_at_network_boundary(self):
        allowed = {
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/lib/net/MessageManager.java",
            "source-downports/modern/1.21.1/neoforge/src/main/java/buildcraft/lib/net/MessageManager.java",
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/lib/net/NeoForgePacketContext.java",
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/lib/net/ForgePacketContext.java",
            "source-family-platforms/legacy/forge/src/main/java/buildcraft/lib/net/MessageManager.java",
            "source-downports/legacy/1.19.2/forge/src/main/java/buildcraft/lib/net/MessageManager.java",
        }
        offenders = []
        roots = ("source-shared", "source-families", "source-platforms", "source-family-platforms", "source-downports", "version-src")
        for root in roots:
            for path in (ROOT / root).rglob("*.java"):
                text = path.read_text(encoding="utf-8", errors="ignore")
                if "NetworkEvent.Context" in text or "IPayloadContext" in text:
                    rel = path.relative_to(ROOT).as_posix()
                    if rel not in allowed:
                        offenders.append(rel)
        self.assertEqual([], offenders)

    def test_platform_java_is_not_loader_neutral(self):
        for platform, token in (("forge", "net.minecraftforge"), ("neoforge", "net.neoforged")):
            root = ROOT / "source-platforms" / platform / "src/main/java"
            stranded = [
                p.relative_to(ROOT).as_posix()
                for p in root.rglob("*.java")
                if token not in p.read_text(encoding="utf-8", errors="ignore")
            ]
            self.assertEqual([], stranded, f"loader-neutral files remain in {platform} platform layer")

    def test_gameplay_uses_neutral_network_side(self):
        for root_name in ("source-shared", "source-families"):
            for path in (ROOT / root_name).rglob("*.java"):
                text = path.read_text(encoding="utf-8", errors="ignore")
                self.assertNotRegex(text, r"\b(?:LogicalSide|PacketFlow)\b", str(path))
        gate = (ROOT / "source-shared/src/main/java/buildcraft/silicon/gate/GateLogic.java").read_text()
        self.assertIn("BCNetworkSide.SERVER", gate)
        self.assertIn("BCNetworkSide.CLIENT", gate)


if __name__ == "__main__":
    unittest.main(verbosity=2)
