#!/usr/bin/env python3
"""Guard the shared installed-server version check against stale CI assertions."""
from __future__ import annotations

import contextlib
import io
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
CHECKS = runpy.run_path(str(ROOT / "scripts/validate-cross-target-integrity.py"))
VALIDATE = CHECKS["validate_ci_wiring"]
PROPERTIES = CHECKS["load_properties"]()


class CIInstalledServerBannerContractTest(unittest.TestCase):
    def test_integrity_validator_accepts_real_shared_banner_gate(self):
        # The real global validator must accept the same implementation the
        # targeted smoke tests exercise, without requiring obsolete variables.
        VALIDATE(PROPERTIES)

    def assert_rejects_removed_guard(self, relative: str, original: str) -> None:
        path = ROOT / relative
        read_text = Path.read_text
        seen = []

        def tampered_read_text(file: Path, *args, **kwargs) -> str:
            value = read_text(file, *args, **kwargs)
            if file == path:
                self.assertIn(original, value)
                seen.append(True)
                return value.replace(original, "# missing required guard", 1)
            return value

        stderr = io.StringIO()
        with patch.object(Path, "read_text", tampered_read_text):
            with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as error:
                VALIDATE(PROPERTIES)
        self.assertEqual(error.exception.code, 1)
        self.assertTrue(seen)
        self.assertIn("smoke", stderr.getvalue().lower())

    def test_rejects_server_that_skips_version_gate(self):
        self.assert_rejects_removed_guard(
            "scripts/ci-server-smoke.sh",
            'if ! buildcraft_startup_banner_matches "$mod_version" "$expected_buildcraft_version" "${log_files[@]}"; then',
        )

    def test_rejects_helper_without_bare_base_version_match(self):
        self.assert_rejects_removed_guard(
            "scripts/ci-smoke-common.sh",
            '"$base_version") return 0',
        )

    def test_rejects_helper_without_bare_target_version_match(self):
        self.assert_rejects_removed_guard(
            "scripts/ci-smoke-common.sh",
            '"$target_version") return 0',
        )

    def test_rejects_helper_without_base_on_loader_match(self):
        self.assert_rejects_removed_guard(
            "scripts/ci-smoke-common.sh",
            '"$base_version on "*) return 0',
        )

    def test_rejects_helper_without_target_on_loader_match(self):
        self.assert_rejects_removed_guard(
            "scripts/ci-smoke-common.sh",
            '"$target_version on "*) return 0',
        )

    def test_rejects_helper_without_exact_banner_extraction(self):
        self.assert_rejects_removed_guard(
            "scripts/ci-smoke-common.sh",
            'banner="${line#*"Starting BuildCraft "}"',
        )


if __name__ == "__main__":
    unittest.main()
