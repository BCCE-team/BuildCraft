#!/usr/bin/env python3
"""Compile API2 fixture addons against an assembled BCCE JAR and Minecraft/loader dependencies.

Example:
  python scripts/verify-addon-binary.py --bcce-jar path/to/buildcraft.jar \
      --dependencies "path/to/minecraft.jar:other-dependency.jar" --release 21

The classpath must contain all external types referenced by the public API.
This is a binary compatibility probe, not a Minecraft runtime or GameTest.
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
FIXTURES = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture"
ADDONS = (
    "CustomPipeFixtureAddon.java",
    "CustomMachineFixtureAddon.java",
    "CustomEngineFixtureAddon.java",
    "AdditionalPipesConformanceAddon.java",
    "ResourceConformanceAddon.java",
    "RobotTaskConformanceAddon.java",
    "PresentationConformanceAddon.java",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bcce-jar", type=Path, required=True, help="Assembled BCCE Java mod JAR")
    parser.add_argument("--dependencies", default="", help="Platform/loader dependency classpath")
    parser.add_argument("--release", type=int, choices=(17, 21, 25), required=True, help="Java target")
    parser.add_argument("--output", type=Path, default=ROOT / "build/conformance/api2-fixtures.jar")
    args = parser.parse_args()

    if not args.bcce_jar.is_file():
        parser.error(f"BuildCraft JAR not found: {args.bcce_jar}")
    with zipfile.ZipFile(args.bcce_jar) as archive:
        for name in ("buildcraft/api/v2/BuildCraftApi.class", "buildcraft/api/v2/BuildCraftRegistries.class"):
            if name not in archive.namelist():
                parser.error(f"BCCE JAR is missing public API entry: {name}")

    files = [FIXTURES / name for name in ADDONS]
    for path in files:
        text = path.read_text(encoding="utf-8")
        if re.search(r"\bbuildcraft\.(?!api\.v2\b)[A-Za-z][\w.]*", text):
            parser.error(f"Fixture depends on a non-public BuildCraft class: {path.name}")

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
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob("*.class")):
                name = path.relative_to(classes).as_posix()
                data = path.read_bytes()
                if re.search(rb"buildcraft/(?!api/v2/)", data):
                    sys.stderr.write(f"Addon bytecode references internal BuildCraft: {name}\n")
                    return 1
                archive.writestr(name, data)
    print(f"Independent API2 addon JAR compiled: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
