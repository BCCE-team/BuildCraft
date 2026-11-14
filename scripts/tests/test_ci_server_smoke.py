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
    def accepts(self, lines: list[str], other: list[str] | None = None) -> bool:
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
                 "_", str(COMMON), "8.0.29", "8.0.29+26.3+fabric", *logs],
                capture_output=True, text=True, check=False,
            )
            self.assertIn(proc.returncode, (0, 1), proc.stderr)
            return proc.returncode == 0

    def test_fabric_target_qualified_version_accepted(self):
        self.assertTrue(self.accepts([
            "[20:27:55] [main/INFO]: Starting BuildCraft 8.0.29+26.3+fabric on Fabric 26.3"
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
