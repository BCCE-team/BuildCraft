#!/usr/bin/env python3
"""Fail-closed checks for the production-JAR addon ABI gate."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / "scripts/verify-addon-binary.py"


class ProductionJarAddonGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("p15_addon_binary", BINARY)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_reject_non_public_buildcraft_import(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "Addon.java"
            path.write_text("import buildcraft.api.v2.OperationMode;\n" +
                            "import buildcraft.transport.pipe.Pipe;\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-public"):
                self.module.ensure_public_sources([path])
            path.write_text("import buildcraft.api.v2.pipe.PipeView;\n", encoding="utf-8")
            self.module.ensure_public_sources([path])

    def test_reject_internal_bytecode_and_empty_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary)
            with self.assertRaisesRegex(ValueError, "No independently"):
                self.module.ensure_public_bytecode(classes)
            unit = classes / "addon/Example.class"
            unit.parent.mkdir(parents=True)
            unit.write_bytes(b"Lbuildcraft/api/v2/pipe/PipeView;")
            self.assertEqual(self.module.ensure_public_bytecode(classes), [unit])
            unit.write_bytes(b"Lbuildcraft/lib/internal/api/v2/BuildCraftApiRuntime;")
            with self.assertRaisesRegex(ValueError, "internal BuildCraft"):
                self.module.ensure_public_bytecode(classes)

    def test_all_three_named_addon_fixtures_are_in_binary_gate(self):
        names = set(self.module.ADDONS)
        for name in ("AdditionalPipesConformanceAddon.java", "LogisticsPipesParityAddon.java",
                     "BuildCraftAdditionsParityAddon.java"):
            self.assertIn(name, names)
        self.module.ensure_public_sources([self.module.FIXTURE_ROOT / name for name in names])

    def test_every_loader_runs_gate_after_jar(self):
        for path in (ROOT / "build-logic/loaders/forge-target.gradle",
                     ROOT / "build-logic/loaders/neoforge-target.gradle"):
            with self.subTest(loader=path.name):
                text = path.read_text(encoding="utf-8")
                self.assertIn("dependsOn tasks.named('jar')", text)
                self.assertIn("tasks.register('verifyApi2AddonBinary', Exec)", text)
                self.assertIn("dependsOn verifyApi2AddonBinary", text)
                self.assertIn("--fixture-root", text)
                self.assertIn("--bcce-jar", text)


if __name__ == "__main__":
    unittest.main()
