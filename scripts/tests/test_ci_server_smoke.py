#!/usr/bin/env python3
"""Installed-server BuildCraft version gate, using the real Bash helper."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / "scripts/ci-smoke-common.sh"
SMOKE = ROOT / "scripts/ci-server-smoke.sh"
BASH = shutil.which("bash")


@unittest.skipUnless(os.name == "posix" and BASH, "requires a POSIX Bash runtime")
class InstalledServerBannerTests(unittest.TestCase):
    def accepts(self, lines: list[str], other: list[str] | None = None,
                target_version: str = "8.0.29+26.3+fabric") -> bool:
        with tempfile.TemporaryDirectory(prefix="bcce-ci-banner-") as tmp:
            first = Path(tmp) / "server.log"
            first.write_text("\n".join(lines) + "\n", encoding="utf-8")
            logs = [str(first)]
            if other is not None:
                second = Path(tmp) / "latest.log"
                second.write_text("\n".join(other) + "\n", encoding="utf-8")
                logs.append(str(second))
            proc = subprocess.run(
                [BASH, "-c", 'source "$1"; shift; buildcraft_startup_banner_matches "$@"',
                 "_", str(COMMON), "8.0.29", target_version, *logs],
                capture_output=True, text=True, check=False,
            )
            self.assertIn(proc.returncode, (0, 1), proc.stderr)
            return proc.returncode == 0

    def test_fabric_target_qualified_version_accepted(self):
        self.assertTrue(self.accepts([
            "[20:27:55] [main/INFO]: Starting BuildCraft 8.0.29+26.3+fabric on Fabric 26.3"
        ]))

    def test_real_installed_neoforge_banner_without_on_is_accepted(self):
        # Log lines from the failed GitHub Actions run 43645486456.
        self.assertTrue(self.accepts([
            "[22:15:56] [modloading-worker-0/INFO] [BuildCraft/]: Starting BuildCraft 8.0.29",
            "[22:16:05] [Server thread/INFO] [minecraft/DedicatedServer]: Done (6.496s)! For help, type \"help\"",
        ], target_version="8.0.29+1.21.1+neoforge"))

    def test_real_installed_forge_banner_without_on_is_accepted(self):
        self.assertTrue(self.accepts([
            "[22:20:16] [modloading-worker-0/INFO] [BuildCraft/]: Starting BuildCraft 8.0.29",
            "[22:20:52] [Server thread/INFO] [minecraft/DedicatedServer]: Done (30.907s)! For help, type \"help\"",
        ], target_version="8.0.29+1.20.1+forge"))

    def test_bare_target_qualified_version_is_accepted(self):
        self.assertTrue(self.accepts([
            "[modloading-worker-0/INFO]: Starting BuildCraft 8.0.29+26.3+fabric"
        ]))

    def test_bare_banner_in_second_log_is_accepted(self):
        self.assertTrue(self.accepts(["Done (4.0s)!"], [
            "[14Nov2026 22:15:56.142] [BuildCraft/]: Starting BuildCraft 8.0.29"
        ]))

    def test_base_version_accepted_for_legacy_targets(self):
        self.assertTrue(self.accepts([
            "[main/INFO] [BuildCraft]: Starting BuildCraft 8.0.29 on NeoForge 26.3"
        ]))

    def test_banner_can_be_in_latest_log(self):
        self.assertTrue(self.accepts(["Unpacking server.jar", "Done (4.0s)!"], [
            "[main/INFO]: Starting BuildCraft 8.0.29+26.3+fabric on Fabric 26.3"
        ]))

    def test_unexpected_or_partial_versions_rejected(self):
        for version in ("8.0.290", "8.0.29+26.3+neoforge", "8.0.29+26.2+fabric",
                        "8.0.29+26.3+fabric-extra", "8.0.28", "$version", "${version}"):
            with self.subTest(version=version):
                self.assertFalse(self.accepts([
                    f"[main/INFO]: Starting BuildCraft {version} on Fabric 26.3"
                ]))

    def test_bare_banners_with_foreign_loader_or_malformed_suffix_are_rejected(self):
        for bad in ("8.0.290", "8.0.29+26.3+neoforge", "8.0.29+26.2+fabric",
                    "8.0.29+26.3+fabric-extra", "8.0.29 unrelated text", "8.0.29on",
                    "8.0.28", "$version", "${version}"):
            with self.subTest(version=bad):
                self.assertFalse(self.accepts([f"[BuildCraft/]: Starting BuildCraft {bad}"]))

    def test_bare_base_version_can_not_hide_wrong_version_or_loader(self):
        # A different target's qualified banner is not valid just because its
        # version begins with the same BCCE release number.
        self.assertFalse(self.accepts([
            "[BuildCraft/]: Starting BuildCraft 8.0.29+26.3+fabric"
        ], target_version="8.0.29+26.3+neoforge"))

    def test_other_text_does_not_substitute_for_startup_banner(self):
        self.assertFalse(self.accepts([
            "[main/INFO]: Loaded Modules: BuildCraft 8.0.29+26.3+fabric",
            "[main/INFO]: Done (3.991s)! For help, type \"help\"",
        ]))

    def test_server_script_calls_exact_version_gate(self):
        self.assertIn('buildcraft_startup_banner_matches "$mod_version" "$expected_buildcraft_version"',
                      SMOKE.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
