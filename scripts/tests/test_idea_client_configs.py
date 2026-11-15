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

    def test_all_committed_configs_launch_the_versioned_power_shell_script(self):
        configs = self.generator.validate_client_configs()
        self.assertEqual(len(configs), 8)
        for c in configs:
            with self.subTest(target=c.target):
                node = ET.parse(ROOT / ".run" / c.filename).getroot().find("configuration")
                self.assertEqual(node.get("type"), "ShConfigurationType")
                opts = {o.get("name"): o.get("value") for o in node.findall("option")}
                self.assertEqual(opts["SCRIPT_PATH"], "$PROJECT_DIR$/run-client.ps1")
                self.assertEqual(opts["SCRIPT_OPTIONS"], c.launcher_args)
                self.assertEqual(opts["SCRIPT_WORKING_DIRECTORY"], "$PROJECT_DIR$")
                self.assertEqual(opts["INDEPENDENT_INTERPRETER_PATH"], "true")
                self.assertEqual(opts["INTERPRETER_PATH"], self.generator.WINDOWS_POWERSHELL)
                self.assertNotIn("$PROJECT_DIR$", opts["INTERPRETER_PATH"])
                self.assertEqual(opts["INTERPRETER_OPTIONS"], "-NoProfile -ExecutionPolicy Bypass -File")
                self.assertEqual(opts["EXECUTE_SCRIPT_FILE"], "true")
                self.assertIsNone(node.find("ExternalSystemSettings"))
                self.assertTrue((ROOT / "run-client.ps1").is_file())

    def test_gradle_daemon_jvm_criteria_match_each_generation(self):
        # Both Gradle 8.8 (preview) and 9.8.1 use the same filename.
        configurations = (
            ("old", "8.8", 17),
            ("1.21.X", "8.8", 21),
            ("26.X", "9.8.1", 25),
        )
        for generation, gradle_version, java_major in configurations:
            with self.subTest(generation=generation):
                build_root = ROOT / "builds" / generation
                wrapper = (build_root / "gradle/wrapper/gradle-wrapper.properties").read_text()
                self.assertIn(f"gradle-{gradle_version}-bin.zip", wrapper)
                criteria = (build_root / "gradle/gradle-daemon-jvm.properties").read_text(encoding="utf-8")
                self.assertEqual(criteria, f"toolchainVersion={java_major}\n")

    def test_shell_client_uses_correct_loader_for_duplicate_versions(self):
        configs = self.generator.client_configs(self.props)
        fabric = next(c for c in configs if c.target == '26.3-fabric')
        neoforge = next(c for c in configs if c.target == '26.3-neoforge')
        self.assertEqual(fabric.launcher_args, '26.3 -Loader fabric')
        self.assertEqual(neoforge.launcher_args, '26.3')
        for target, args in ((fabric, '26.3 -Loader fabric'), (neoforge, '26.3')):
            self.assertIn(f'SCRIPT_OPTIONS" value="{args}"', self.generator.config_xml(target))

    def test_generator_rejects_bypassing_the_jdk_selector(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.generator.write_client_configs(directory, self.props)
            wrong = directory / 'Run Client 26.2 (NeoForge).run.xml'
            wrong.write_text(wrong.read_text(encoding='utf-8').replace(
                'ShConfigurationType', 'GradleRunConfiguration'), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'wrong IDEA|Wrong IDEA'):
                self.generator.validate_client_configs(directory, self.props)

    def test_manual_idea_interpreter_override_fails_validation(self):
        # IDEA previously rewrote C:\Windows as a PROJECT_DIR-relative path
        # and flipped INDEPENDENT_INTERPRETER_PATH to false. That breaks CI and
        # can break launches when the repository is moved to another directory.
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.generator.write_client_configs(directory, self.props)
            run = directory / "Run Client 1.19.2 (Forge).run.xml"
            original = run.read_text(encoding="utf-8")
            run.write_text(original.replace(
                'name="INDEPENDENT_INTERPRETER_PATH" value="true"',
                'name="INDEPENDENT_INTERPRETER_PATH" value="false"'), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "INDEPENDENT_INTERPRETER_PATH"):
                self.generator.validate_client_configs(directory, self.props)
            run.write_text(original.replace(
                self.generator.WINDOWS_POWERSHELL,
                "$PROJECT_DIR$/../../../../Windows/System32/WindowsPowerShell/v1.0/powershell.exe"),
                encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "INTERPRETER_PATH"):
                self.generator.validate_client_configs(directory, self.props)

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
                'SCRIPT_OPTIONS" value="26.3 -Loader fabric"', 'SCRIPT_OPTIONS" value="26.3"'), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "SCRIPT_OPTIONS"):
                self.generator.validate_client_configs(directory, self.props)

    def test_duplicate_loader_version_is_error(self):
        props = self.props.copy()
        props["targets"] += ",26.3-neoforge"
        with self.assertRaisesRegex(ValueError, "Duplicate IDEA"):
            self.generator.client_configs(props)


if __name__ == "__main__":
    unittest.main()
