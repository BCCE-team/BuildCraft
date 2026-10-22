#!/usr/bin/env python3
"""Import the *separate* BCCE-team/Localizations repository into BuildCraft jars.

Only BuildCraft translations are selected. The canonical en_us stays in BCCE,
and Iron Tanks resources never enter the staged tree. Invoked by Gradle for
all Forge/NeoForge Stonecutter targets, and by CI to inspect production jars.
"""

from __future__ import annotations

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile

DEFAULT_REMOTE = "https://github.com/BCCE-team/Localizations.git"
LOCALE_PATTERN = re.compile(r"^[a-z]{2,3}_[a-z]{2,3}$")
RESOURCE_FOLDERS = (
    ("buildcraft/lang", "assets/buildcraft/lang"),
    ("buildcraft/guide/text", "assets/buildcraft/guide/text"),
)


class LocalizationError(Exception):
    """Reportable input or packaging error."""


def load_json_object(path: Path) -> dict:
    def no_duplicate_keys(items: list[tuple[str, object]]) -> dict:
        result = {}
        for key, value in items:
            if key in result:
                raise LocalizationError(f"Duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=no_duplicate_keys)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise LocalizationError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise LocalizationError(f"Expected a JSON object in {path}")
    return data


def list_resources(source: Path, english: Path) -> dict[str, Path]:
    if not english.is_file():
        raise LocalizationError(f"Canonical English translation missing: {english}")
    en_us = load_json_object(english)
    if not en_us:
        raise LocalizationError(f"Empty canonical English translation: {english}")

    if not (source / "buildcraft" / "lang").is_dir():
        raise LocalizationError(
            f"No BuildCraft languages found in {source}. Expected buildcraft/lang/*.json "
            "from the separate BCCE-team/Localizations repository."
        )

    resources: dict[str, Path] = {}
    for source_folder, target_folder in RESOURCE_FOLDERS:
        folder = source / source_folder
        if not folder.exists():
            continue
        for path in sorted(folder.iterdir()):
            if not path.is_file() or path.suffix != ".json":
                continue
            locale = path.stem
            if locale == "en_us":
                # English is authoritative in BuildCraft; never shadow it.
                continue
            if not LOCALE_PATTERN.fullmatch(locale):
                raise LocalizationError(f"Unexpected language filename: {path}")
            data = load_json_object(path)
            if source_folder == "buildcraft/lang":
                for key, value in data.items():
                    if not isinstance(key, str) or not isinstance(value, str):
                        raise LocalizationError(f"Non-string translation {key!r} in {path}")
            else:
                if data.get("format") != 1 or data.get("language") != locale or not isinstance(data.get("pages"), dict):
                    raise LocalizationError(f"Invalid BuildCraft guide translation schema in {path}")
            resource = f"{target_folder}/{path.name}"
            if resource in resources:
                raise LocalizationError(f"Duplicate output resource {resource}")
            resources[resource] = path

    if not any(resource.startswith("assets/buildcraft/lang/") for resource in resources):
        raise LocalizationError(f"No languages to package from {source}")
    return resources


def run_git(*arguments: str, cwd: Path | None = None) -> str:
    try:
        process = subprocess.run(
            ["git", *arguments], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace", timeout=120, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise LocalizationError(f"Unable to run Git: {exc}") from exc
    if process.returncode:
        raise LocalizationError(
            f"git {' '.join(arguments[:3])} failed: {process.stderr.strip() or process.stdout.strip()}"
        )
    return process.stdout.strip()


def discover_checkout(repo_root: Path) -> Path | None:
    # CI's pinned checkout takes priority; support sibling clones and the
    # legacy optional submodule without tracking the JSON files inside BCCE.
    candidates = (
        # Use the CI checkout first: releases pin a specific localization commit.
        repo_root / "build" / "external" / "localizations",
        repo_root.parent / "Localizations",
        repo_root.parent / "Localizations-main",
        # Existing old-named local checkouts should keep working.
        repo_root.parent / "BuildCraft-Localizations",
        repo_root.parent / "BuildCraft-Localizations-main",
        repo_root / "localizations",
    )
    for path in candidates:
        if (path / "buildcraft" / "lang").is_dir():
            return path.resolve()
    return None


def resolve_source(args: argparse.Namespace) -> Path:
    if args.source:
        source = Path(args.source).expanduser()
        if not source.is_absolute():
            source = args.root / source
        source = source.resolve()
        if not source.is_dir():
            raise LocalizationError(
                f"Configured localization checkout does not exist: {source}. "
                "Supply -PbcceLocalizationsDir=<path> or BCCE_LOCALIZATIONS_DIR."
            )
        return source

    discovered = discover_checkout(args.root)
    if discovered:
        return discovered

    cache = args.cache.resolve()
    if (cache / ".git").exists():
        if not args.offline:
            run_git("fetch", "--quiet", "--no-tags", "--depth=1", "origin", args.ref, cwd=cache)
            run_git("checkout", "--quiet", "--detach", "--force", "FETCH_HEAD", cwd=cache)
        return cache

    if args.offline:
        raise LocalizationError(
            "BuildCraft localizations missing and Gradle is offline. Clone "
            "https://github.com/BCCE-team/Localizations beside the mod "
            "or specify -PbcceLocalizationsDir=<path>."
        )
    cache.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bcce-localizations-fetch-", dir=cache.parent) as temporary:
        checkout = Path(temporary) / "checkout"
        run_git("init", "--quiet", str(checkout))
        run_git("remote", "add", "origin", args.remote, cwd=checkout)
        run_git("fetch", "--quiet", "--no-tags", "--depth=1", "origin", args.ref, cwd=checkout)
        run_git("checkout", "--quiet", "--detach", "--force", "FETCH_HEAD", cwd=checkout)
        if cache.exists():
            raise LocalizationError(f"Localization cache already exists but is not a Git checkout: {cache}")
        shutil.move(str(checkout), cache)
    return cache


def stage_resources(args: argparse.Namespace) -> None:
    source = resolve_source(args)
    resources = list_resources(source, args.english)
    output = args.output.resolve()
    if output == source or source in output.parents or output in source.parents:
        raise LocalizationError("Localization staging must not overwrite the source checkout")
    if output == args.root:
        raise LocalizationError("Localization staging must not overwrite the repository root")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bcce-localizations-stage-", dir=output.parent) as temporary:
        temporary_root = Path(temporary) / "resources"
        for resource, original in resources.items():
            target = temporary_root / resource
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, target)
        if output.exists():
            shutil.rmtree(output)
        shutil.move(str(temporary_root), output)
    count_lang = sum(name.startswith("assets/buildcraft/lang/") for name in resources)
    count_guide = len(resources) - count_lang
    print(f"BuildCraft localizations: {count_lang} languages + {count_guide} guide translations from {source}")
    print(f"Staged {len(resources)} external JSON resources into {output}")
    if (source / ".git").exists():
        try:
            print(f"Localization revision: {run_git('rev-parse', 'HEAD', cwd=source)}")
        except LocalizationError:
            pass


def verify_jar(args: argparse.Namespace) -> None:
    source = args.source.resolve()
    resources = list_resources(source, args.english)
    if not args.jar.is_file():
        raise LocalizationError(f"Missing BuildCraft JAR: {args.jar}")
    with ZipFile(args.jar) as archive:
        files = archive.namelist()
        names = set(files)
        counts = Counter(files)
        duplicated = [name for name, count in counts.items() if count != 1 and
                      (name.startswith("assets/buildcraft/lang/") or name.startswith("assets/buildcraft/guide/text/"))]
        if duplicated:
            raise LocalizationError(f"Duplicate BuildCraft localization ZIP entries: {sorted(duplicated)}")
        for name, original in resources.items():
            if name not in names:
                raise LocalizationError(f"Missing localization in JAR: {name}")
            if archive.read(name) != original.read_bytes():
                raise LocalizationError(f"JAR resource does not match external localization source: {name}")
        external_in_jar = {
            name for name in names if name.endswith(".json") and
            (name.startswith("assets/buildcraft/lang/") or name.startswith("assets/buildcraft/guide/text/")) and
            not name.endswith("/en_us.json")
        }
        unexpected = external_in_jar - resources.keys()
        if unexpected:
            raise LocalizationError(f"Untracked or stale translation resources in JAR: {sorted(unexpected)}")
        for required in ("assets/buildcraft/lang/en_us.json", "assets/buildcraft/guide/text/en_us.json"):
            if required not in names:
                raise LocalizationError(f"Missing built-in English resource in JAR: {required}")
        if any(name.startswith("assets/irontanks/lang/") or name.startswith("assets/irontanks/guide/text/") for name in names):
            raise LocalizationError("Iron Tanks translations must not be embedded in BuildCraft")
    print(f"Verified {len(resources)} external translations and both English resources in {args.jar}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("stage", help="materialize external JSON into Gradle's ignored build directory")
    stage.add_argument("--root", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    stage.add_argument("--cache", type=Path, required=True)
    stage.add_argument("--english", type=Path, required=True)
    stage.add_argument("--source", help="external checkout; overrides sibling/CI/automatic Git checkout")
    stage.add_argument("--ref", default="main", help="Git revision used only for the automatic checkout")
    stage.add_argument("--remote", default=DEFAULT_REMOTE)
    stage.add_argument("--offline", action="store_true")
    verify = commands.add_parser("verify-jar", help="verify external translations are in the production JAR")
    verify.add_argument("--source", type=Path, required=True)
    verify.add_argument("--english", type=Path, required=True)
    verify.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "stage":
            args.root = args.root.resolve()
            stage_resources(args)
        else:
            verify_jar(args)
    except (LocalizationError, OSError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
