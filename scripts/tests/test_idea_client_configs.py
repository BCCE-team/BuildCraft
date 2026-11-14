"""Regressions for IntelliJ IDEA loader-qualified client runtime configs."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def load_generator():
    import sys
    script_root = ROOT / "scripts"
    if str(script_root) not in sys.path:
        sys.path.insert(0, str(script_root))
    # Import through the regular module mechanism: dataclasses resolves it via sys.modules.
    import idea_client_configs
    return idea_client_configs


class IdeaClientConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.generator = load_generator()
        cls.props = cls.generator.load_properties()

    def test_all_targets_have_unique_loader_qualified_names(self):
        configs = self.generator.client_configs(self.props)
        self.assertEqual(len(configs), len(self.generator.target_ids(self.props)))
        self.assertEqual(len({c.name for c in configs}), len(configs))
        self.assertEqual(len({c.filename for c in configs}), len(configs))
        self.assertIn("Run Client 26.3 (Fabric)", {c.name for c in configs})
        self.assertIn("Run Client 26.3 (NeoForge)", {c.name for c in configs})

    def test_all_committed_configs_point_to_exact_target_and_generation(self):
        configs = self.generator.validate_client_configs()
        self.assertEqual(len(configs), 8)
        for c in configs:
            with self.subTest(target=c.target):
                path = ROOT / ".run" / c.filename
                xml = ET.parse(path).getroot()
                opts = {o.get("name"): o for o in xml.findall("configuration/ExternalSystemSettings/option")}
                self.assertEqual(opts["externalProjectPath"].get("value"),
                                 f"$PROJECT_DIR$/builds/{c.generation}")
                self.assertEqual(opts["taskNames"].find("list/option").get("value"),
                                 f":{c.target}:runClient")

    def test_generation_works_for_another_double_loader_version(self):
        props = self.props.copy()
        extra = []
        for loader in ("neoforge", "fabric"):
            t = f"26.4-{loader}"
            extra.append(t)
            props[f"target.{t}.deps.minecraft"] = "26.4"
            props[f"target.{t}.source.platform"] = loader
            props[f"target.{t}.build.generation"] = "26.X"
        props["targets"] = props["targets"] + "," + ",".join(extra)
        configs = self.generator.client_configs(props)
        for t in extra:
            c = next(c for c in configs if c.target == t)
            self.assertEqual(c.project_path, "$PROJECT_DIR$/builds/26.X")
            self.assertEqual(c.gradle_task, f":{t}:runClient")
        self.assertEqual(len({c.name for c in configs}), len(configs))

    def test_generator_rewrites_stale_files_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            previous = ROOT / ".run" / "Run Client 26.3.run.xml"
            # A legacy configuration for the NeoForge-only 26.3 client.
            old = self.generator.config_xml(next(c for c in self.generator.client_configs(self.props)
                                                     if c.target == "26.3-neoforge"))
            old = old.replace('name="Run Client 26.3 (NeoForge)"',
                              'name="Run Client 26.3"')
            (directory / previous.name).write_text(old, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "out of sync"):
                self.generator.validate_client_configs(directory, self.props)
            self.generator.write_client_configs(directory, self.props)
            self.assertFalse((directory / previous.name).exists())
            generated = {p.name: p.read_bytes() for p in directory.glob("*.run.xml")}
            self.generator.write_client_configs(directory, self.props)
            self.assertEqual(generated, {p.name: p.read_bytes() for p in directory.glob("*.run.xml")})

    def test_check_rejects_wrong_loader_task_and_duplicate_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.generator.write_client_configs(directory, self.props)
            wrong = directory / "Run Client 26.3 (Fabric).run.xml"
            wrong.write_text(wrong.read_text(encoding="utf-8").replace(
                ":26.3-fabric:runClient", ":26.3-neoforge:runClient"), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, ":26.3-fabric:runClient"):
                self.generator.validate_client_configs(directory, self.props)

    def test_duplicate_loader_version_is_error(self):
        props = self.props.copy()
        props["targets"] += ",26.3-neoforge"
        with self.assertRaisesRegex(ValueError, "Duplicate IDEA"):
            self.generator.client_configs(props)


if __name__ == "__main__":
    unittest.main()
