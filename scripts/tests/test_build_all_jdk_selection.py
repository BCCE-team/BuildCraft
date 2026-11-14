"""Regression tests for build-all Windows JDK selection."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load_selector():
    path = ROOT / "scripts" / "select-build-java.py"
    spec = importlib.util.spec_from_file_location("build_all_java_selector", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildAllJavaTests(unittest.TestCase):
    def setUp(self):
        self.selector = load_selector()
        self.powershell = (ROOT / "build-all.ps1").read_text(encoding="utf-8")

    def test_all_three_generations_have_distinct_versions(self):
        for fragment in ('Generation = "old"; Java = 17',
                         'Generation = "1.21.X"; Java = 21',
                         'Generation = "26.X"; Java = 25'):
            self.assertIn(fragment, self.powershell)

    def test_runtime_environment_is_set_for_each_gradle_process(self):
        self.assertIn('$env:JAVA_HOME = $HomeDir', self.powershell)
        self.assertIn('$env:PATH = (Join-Path $HomeDir "bin")', self.powershell)
        self.assertIn('$env:PATH = $OriginalPath', self.powershell)
        self.assertIn('Remove-Item Env:JAVA_HOME', self.powershell)
        self.assertIn('select-build-java.py', self.powershell)
        self.assertIn('break', self.powershell)

    def test_selector_uses_only_matching_jdk_versions(self):
        compiler = "javac.exe" if self.selector.sys.platform == "win32" else "javac"
        with tempfile.TemporaryDirectory() as temporary:
            roots = {}
            for version in (17, 21, 25):
                home = Path(temporary) / str(version)
                (home / "bin").mkdir(parents=True)
                (home / "bin" / compiler).touch()
                roots[version] = [home]
            # Prefer a real JDK to a JRE of the same major version.
            jre = Path(temporary) / "jre25"
            roots[25].insert(0, jre)
            selected = self.selector.select_jdks([17, 21, 25], roots, None)
            self.assertEqual(selected, {str(v): str(Path(temporary) / str(v)) for v in (17, 21, 25)})

    def test_missing_jdk_is_explicit_error(self):
        with self.assertRaisesRegex(self.selector.JavaSelectionError, "25"):
            self.selector.select_jdks([25], {17: [Path("/any/17")]},
                type("Reporter", (), {"format_java_diagnostics": staticmethod(lambda available: "diagnostics")})())

    def test_cmd_wrapper_invokes_same_logic(self):
        script = (ROOT / "build-all.bat").read_text(encoding="utf-8")
        self.assertIn('build-all.ps1', script)
        self.assertIn('exit /b %ERRORLEVEL%', script)


if __name__ == "__main__":
    unittest.main()
