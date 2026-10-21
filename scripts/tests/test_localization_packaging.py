"""Regression coverage for externally sourced BuildCraft localization JAR resources."""

from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/embed_localizations.py"


class ExternalLocalizationPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "separate-repository"
        self.english = self.root / "main-repo-english.json"
        self.output = self.root / "generated-resources"
        self.cache = self.root / "ignored-git-cache"
        self.english.write_text(json.dumps({"item.buildcraft.widget": "Widget"}), encoding="utf-8")
        self.create_locale("ru_ru", "Виджет")
        # The external repository also maintains another mod. Never package it.
        iron = self.source / "irontanks/lang/ru_ru.json"
        iron.parent.mkdir(parents=True, exist_ok=True)
        iron.write_text('{"item.irontanks.tank": "Бак"}', encoding="utf-8")
        self.own_en_us = self.source / "buildcraft/lang/en_us.json"
        self.own_en_us.write_text('{"item.buildcraft.widget": "Wrong override"}', encoding="utf-8")

    def create_locale(self, language: str, translation: str):
        language_file = self.source / "buildcraft/lang" / f"{language}.json"
        language_file.parent.mkdir(parents=True, exist_ok=True)
        language_file.write_text(
            json.dumps({"item.buildcraft.widget": translation}, ensure_ascii=False), encoding="utf-8"
        )
        guide = self.source / "buildcraft/guide/text" / f"{language}.json"
        guide.parent.mkdir(parents=True, exist_ok=True)
        guide.write_text(
            json.dumps({"format": 1, "language": language, "pages": {"buildcraftcore/test": [translation]}}, ensure_ascii=False),
            encoding="utf-8",
        )

    def run_stage(self, *, source=None, offline=True, remote=None, expect_ok=True):
        command = [
            sys.executable, str(SCRIPT), "stage", "--root", str(self.root / "mod"),
            "--english", str(self.english), "--output", str(self.output),
            "--cache", str(self.cache), "--ref", "main",
        ]
        if source is not None:
            command.extend(["--source", str(source)])
        if remote is not None:
            command.extend(["--remote", str(remote)])
        if offline:
            command.append("--offline")
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
        if expect_ok:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def test_stage_only_buildcraft_non_english_resources(self):
        self.run_stage(source=self.source)
        staged = sorted(str(path.relative_to(self.output)) for path in self.output.rglob("*.json"))
        self.assertEqual(staged, [
            "assets/buildcraft/guide/text/ru_ru.json",
            "assets/buildcraft/lang/ru_ru.json",
        ])
        self.assertEqual(
            (self.output / "assets/buildcraft/lang/ru_ru.json").read_bytes(),
            (self.source / "buildcraft/lang/ru_ru.json").read_bytes(),
        )
        self.assertNotIn("en_us.json", "\n".join(staged))
        self.assertNotIn("irontanks", "\n".join(staged))

    def test_restaging_removes_deleted_languages(self):
        self.create_locale("fr_fr", "Widget traduit")
        self.run_stage(source=self.source)
        self.assertTrue((self.output / "assets/buildcraft/lang/fr_fr.json").exists())
        (self.source / "buildcraft/lang/fr_fr.json").unlink()
        (self.source / "buildcraft/guide/text/fr_fr.json").unlink()
        self.run_stage(source=self.source)
        self.assertFalse((self.output / "assets/buildcraft/lang/fr_fr.json").exists())
        self.assertFalse((self.output / "assets/buildcraft/guide/text/fr_fr.json").exists())

    def test_reject_corrupt_json_without_destroying_existing_stage(self):
        self.run_stage(source=self.source)
        original = (self.output / "assets/buildcraft/lang/ru_ru.json").read_bytes()
        (self.source / "buildcraft/lang/ru_ru.json").write_text('{"bad":', encoding="utf-8")
        result = self.run_stage(source=self.source, expect_ok=False)
        self.assertIn("Invalid JSON", result.stderr)
        self.assertEqual(original, (self.output / "assets/buildcraft/lang/ru_ru.json").read_bytes())

    def test_reject_duplicate_translation_keys(self):
        (self.source / "buildcraft/lang/ru_ru.json").write_text('{"x":"1","x":"2"}', encoding="utf-8")
        result = self.run_stage(source=self.source, expect_ok=False)
        self.assertIn("Duplicate JSON key", result.stderr)

    def test_reject_mismatched_guide_language(self):
        (self.source / "buildcraft/guide/text/ru_ru.json").write_text(
            '{"format":1,"language":"en_us","pages":{}}', encoding="utf-8"
        )
        result = self.run_stage(source=self.source, expect_ok=False)
        self.assertIn("guide translation schema", result.stderr)

    def test_missing_external_source_in_offline_mode_fails(self):
        result = self.run_stage(expect_ok=False)
        self.assertIn("Gradle is offline", result.stderr)

    def test_production_jar_verification(self):
        self.run_stage(source=self.source)
        jar = self.root / "mod.jar"
        with ZipFile(jar, "w") as package:
            for path in self.output.rglob("*.json"):
                package.write(path, path.relative_to(self.output).as_posix())
            package.writestr("assets/buildcraft/lang/en_us.json", self.english.read_bytes())
            package.writestr("assets/buildcraft/guide/text/en_us.json", '{"format":1,"language":"en_us","pages":{}}')
        verify_command = [sys.executable, str(SCRIPT), "verify-jar", "--source", str(self.source),
                          "--english", str(self.english), "--jar", str(jar)]
        result = subprocess.run(verify_command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        with ZipFile(jar, "a") as package:
            package.writestr("assets/buildcraft/lang/ru_ru.json", "changed")
        result = subprocess.run(verify_command, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Duplicate BuildCraft", result.stderr)

    def test_git_checkout_cache_supports_offline_rebuild_and_updates(self):
        if shutil.which("git") is None:
            self.skipTest("Git is unavailable")
        remote = self.root / "remote"
        shutil.copytree(self.source, remote)
        for cmd in (["git", "init", "-q", "-b", "main"], ["git", "add", "."],
                    ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "initial"]):
            subprocess.run(cmd, cwd=remote, check=True, capture_output=True)
        self.run_stage(remote=remote, offline=False)
        self.assertTrue((self.cache / ".git").is_dir())
        self.assertTrue((self.output / "assets/buildcraft/lang/ru_ru.json").exists())
        self.run_stage(remote=remote, offline=True)
        new_file = remote / "buildcraft/lang/ru_ru.json"
        new_file.write_text('{"item.buildcraft.widget":"Новинка"}', encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=remote, check=True, capture_output=True)
        subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "update"],
                       cwd=remote, check=True, capture_output=True)
        self.run_stage(remote=remote, offline=False)
        self.assertEqual(
            (self.output / "assets/buildcraft/lang/ru_ru.json").read_bytes(), new_file.read_bytes()
        )

    def test_non_english_translations_are_not_stored_in_mod_sources(self):
        maintained = ("source-shared", "source-families", "source-platforms",
                      "source-family-platforms", "source-downports", "version-src", "resource-src")
        for name in maintained:
            for path in (ROOT / name).rglob("*.json"):
                if "assets/buildcraft/lang/" in path.as_posix() or "assets/buildcraft/guide/text/" in path.as_posix():
                    self.assertEqual(path.name, "en_us.json", f"External translation committed to mod sources: {path}")

    def test_new_repository_checkout_is_discovered_and_ci_checkout_is_preferred(self):
        module_root = self.root / "mod"
        module_root.mkdir(parents=True, exist_ok=True)
        sibling = module_root.parent / "Localizations"
        self.create_locale("de_de", "Beispiel")
        shutil.copytree(self.source, sibling)
        self.run_stage()
        self.assertTrue((self.output / "assets/buildcraft/lang/de_de.json").exists())
        ci = module_root / "build/external/localizations"
        shutil.copytree(self.source, ci)
        (ci / "buildcraft/lang/de_de.json").write_text('{"item.buildcraft.widget":"CI pin"}', encoding="utf-8")
        self.run_stage()
        self.assertEqual(
            (self.output / "assets/buildcraft/lang/de_de.json").read_text(encoding="utf-8"),
            '{"item.buildcraft.widget":"CI pin"}',
        )

    def test_gradle_and_ci_package_all_loader_targets(self):
        common = (ROOT / "build-logic/common-target.gradle").read_text(encoding="utf-8")
        for item in ("tasks.named('processResources', ProcessResources)",
                     "dependsOn stageBuildCraftLocalizations", "from(externalLocalizationsOutput)",
                     "tasks.register('stageBuildCraftLocalizations', Exec)"):
            self.assertIn(item, common)
        for adapter in ("forge-target.gradle", "neoforge-target.gradle"):
            text = (ROOT / "build-logic/loaders" / adapter).read_text(encoding="utf-8")
            self.assertIn("build-logic/common-target.gradle", text)
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        release = (ROOT / ".github/workflows/publish.yml").read_text(encoding="utf-8")
        for text in (ci, release):
            self.assertIn("repository: BCCE-team/Localizations", text)
            self.assertIn("embed_localizations.py verify-jar", text)
            self.assertIn("path: build/external/localizations", text)
        self.assertIn("https://github.com/BCCE-team/Localizations.git", common)
        self.assertIn("https://github.com/BCCE-team/Localizations.git", (ROOT / "scripts/embed_localizations.py").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
