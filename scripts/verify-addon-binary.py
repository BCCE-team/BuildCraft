#!/usr/bin/env python3
"""Compile independent addon fixtures against the built BCCE production JAR.

This requires target Minecraft/loader compile dependencies. It tests binary linkage,
not successful loading of historical mod JARs or in-game GameTest behavior.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture"
ADDONS = (
    "CustomPipeFixtureAddon.java",
    "CustomMachineFixtureAddon.java",
    "CustomEngineFixtureAddon.java",
    "AdditionalPipesConformanceAddon.java",
    "ResourceConformanceAddon.java",
    "RobotTaskConformanceAddon.java",
    "PresentationConformanceAddon.java",
    "LogisticsPipesParityAddon.java",
    "BuildCraftAdditionsParityAddon.java",
)

INTERNAL_IMPORT = re.compile(r"\bbuildcraft\.(?!api\.v2\b)[A-Za-z][\w.]*")
INTERNAL_BYTECODE = re.compile(rb"buildcraft/(?!api/v2/)")


def ensure_public_sources(paths: list[Path]) -> None:
    for path in paths:
        if not path.is_file():
            raise ValueError(f"Missing independent addon fixture: {path}")
        if INTERNAL_IMPORT.search(path.read_text(encoding="utf-8")):
            raise ValueError(f"Fixture references a non-public BuildCraft class: {path}")


def ensure_public_bytecode(classes: Path) -> list[Path]:
    files = sorted(classes.rglob("*.class"))
    if not files:
        raise ValueError("No independently compiled addon classes")
    for path in files:
        if INTERNAL_BYTECODE.search(path.read_bytes()):
            raise ValueError(f"Addon bytecode references internal BuildCraft: {path.relative_to(classes)}")
    return files


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bcce-jar", type=Path, required=True, help="Built BCCE mod JAR")
    parser.add_argument("--dependencies", default="", help="Target Minecraft/loader compile classpath")
    parser.add_argument("--release", type=int, choices=(17, 21, 25), required=True)
    parser.add_argument("--fixture-root", type=Path, default=FIXTURE_ROOT,
                        help="Per-target materialized addon fixtures, not internal BuildCraft sources")
    parser.add_argument("--output", type=Path, default=ROOT / "build/conformance/api2-fixtures.jar")
    args = parser.parse_args(argv)

    if not args.bcce_jar.is_file():
        parser.error(f"BuildCraft JAR not found: {args.bcce_jar}")
    try:
        with zipfile.ZipFile(args.bcce_jar) as archive:
            for name in ("buildcraft/api/v2/BuildCraftApi.class", "buildcraft/api/v2/BuildCraftRegistries.class"):
                if name not in archive.namelist():
                    parser.error(f"Production BCCE JAR is missing public API entry: {name}")
    except zipfile.BadZipFile as error:
        parser.error(f"Invalid BCCE JAR: {error}")

    files = [args.fixture_root / name for name in ADDONS]
    try:
        ensure_public_sources(files)
    except ValueError as error:
        parser.error(str(error))

    classpath = os.pathsep.join(filter(None, (str(args.bcce_jar.resolve()), args.dependencies)))
    with tempfile.TemporaryDirectory(prefix="bcce-api2-external-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        command = ["javac", "--release", str(args.release), "-encoding", "UTF-8", "-sourcepath", "",
                   "-cp", classpath, "-d", str(classes), *map(str, files)]
        result = subprocess.run(command, text=True, capture_output=True, check=False)
        if result.returncode:
            sys.stderr.write(result.stdout + result.stderr)
            return result.returncode
        try:
            verified = ensure_public_bytecode(classes)
        except ValueError as error:
            sys.stderr.write(f"{error}\n")
            return 1
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in verified:
                archive.write(path, path.relative_to(classes).as_posix())
    print(f"Independent API v2 addon fixtures compiled against {args.bcce_jar}: {len(verified)} classes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
