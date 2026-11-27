#!/usr/bin/env python3
"""Compile independent addon fixtures against the built BCCE production JAR.

This requires target Minecraft/loader compile dependencies. It tests binary linkage,
not successful loading of third-party mod JARs or in-game GameTest behavior.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

from addon_fixture_common import (
    ROOT,
    API_FIXTURE_ADDONS as ADDONS,
    API_FIXTURE_SOURCE_ROOT as FIXTURE_ROOT,
    ensure_public_bytecode,
    ensure_public_sources,
    fixture_source_files,
    extract_public_api_classes,
    javac_command,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bcce-jar", type=Path, required=True, help="Built BCCE mod JAR")
    parser.add_argument("--dependencies", default="", help="Target Minecraft/loader compile classpath")
    parser.add_argument("--release", type=int, choices=(17, 21, 25), required=True)
    parser.add_argument("--javac", default="javac", help="Target JDK compiler executable (normally provided by Gradle toolchains)")
    parser.add_argument("--fixture-root", type=Path, default=FIXTURE_ROOT,
                        help="Per-target materialized addon fixtures, not internal BuildCraft sources")
    parser.add_argument("--output", type=Path, default=ROOT / "build/conformance/api2-fixtures.jar")
    args = parser.parse_args(argv)

    if not args.bcce_jar.is_file():
        parser.error(f"BuildCraft JAR not found: {args.bcce_jar}")
    try:
        files = fixture_source_files(args.fixture_root)
        ensure_public_sources(files)
    except ValueError as error:
        parser.error(str(error))

    with tempfile.TemporaryDirectory(prefix="bcce-api2-external-") as temporary:
        temp = Path(temporary)
        classes = temp / "classes"
        classes.mkdir()
        api = temp / 'public-api.jar'
        try:
            extract_public_api_classes(args.bcce_jar, api,
                required_classes=('BuildCraftApi.class', 'BuildCraftRegistries.class'))
        except (ValueError, OSError, zipfile.BadZipFile) as error:
            parser.error(str(error))
        command = javac_command(args.javac, args.release, classes, api, files, args.dependencies)
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
