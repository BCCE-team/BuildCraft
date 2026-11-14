#!/usr/bin/env python3
"""Fail-closed static integration contract for the 26.3 Fabric production target.

Checks the native source tree, real CI/release wiring, and Fabric bootstrap.
Runtime success is proven only by Gradle/GameTests and installed-server IT16.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import tempfile

from source_layout import ROOT, load_properties, materialize_target, target_ids, target_layout

TARGET = "26.3-fabric"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def contains(path: str, *needles: str) -> None:
    data = (ROOT / path).read_text(encoding="utf-8")
    for needle in needles:
        require(needle in data, f"{path}: missing {needle!r}")


def validate() -> None:
    props = load_properties()
    require(TARGET in target_ids(props), "26.3-fabric is not registered in the canonical matrix")
    layout = target_layout(TARGET, props)
    require(layout.family == "26.X" and layout.platform == "fabric", "Fabric 26.3 has the wrong source family/platform")
    for key in ("fabric_loader", "fabric_api", "fabric_installer", "energy", "jei", "jade"):
        require(props.get(f"target.{TARGET}.deps.{key}"), f"Missing pinned Fabric dependency {key}")
    require(props.get(f"target.{TARGET}.java.version") == "25", "Fabric 26.3 must use JDK 25")
    require(props.get(f"target.{TARGET}.network.protocol") == props.get("target.26.3-neoforge.network.protocol"),
            "Network protocol diverges between 26.3 loaders")

    manifest = ROOT / props[f"target.{TARGET}.source.exclusion_manifest"]
    require(manifest.is_file(), "Missing Fabric-only exclusion manifest")
    excludes = [line for line in manifest.read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.startswith("#")]
    require(len(excludes) == len(set(excludes)) and len(excludes) > 0,
            "Fabric exclusion manifest is empty/duplicated")
    require(not any(".." in Path(line).parts or line.startswith("/") for line in excludes),
            "Fabric exclusion manifest contains a path escape")

    contains("builds/26.X/build.fabric.gradle", "net.fabricmc.fabric-loom", "loaders/fabric-target.gradle")
    contains("builds/26.X/settings.gradle.kts", "https://maven.fabricmc.net/")
    contains("build-logic/common-target.gradle", "targetAccessWidener", "prepareEffectiveSource")
    contains("build-logic/loaders/fabric-target.gradle",
             "accessWidenerPath", "configureTests", "runGameTest", "fabricDistributionMods",
             "runtimeOnly requiredProperty('deps.jei')", "runtimeOnly requiredProperty('deps.jade')",
             "collectFabricServerMods", "prepareEffectiveSource", "buildAndCollect")
    contains("scripts/ci-server-smoke.sh", "fabric-installer", "fabric-server-launch.jar", "collectFabricServerMods")
    contains("scripts/ci-modpack-world-join.py", '"26.3-fabric": "26.X"', "ci_world_join")
    contains("scripts/run-ci-local.py", '("26.3-fabric", "26.X", 25)')
    contains(".github/workflows/ci.yml", "- target: 26.3-fabric", "Collect installed Fabric server dependencies")
    contains(".github/workflows/publish.yml", "26.3-fabric", "Publish 26.3 Fabric", "loaders: fabric")

    lock = json.loads((ROOT / "build-config/modpack-world-join-lock.json").read_text(encoding="utf-8"))
    require(TARGET in lock.get("targets", {}), "Fabric 26.3 is absent from the locked client/server test")
    require(lock["targets"][TARGET]["generation"] == "26.X", "Invalid Fabric world-join generation")

    with tempfile.TemporaryDirectory(prefix="buildcraft-fabric-26.3-") as tmp:
        dest = Path(tmp) / "target"
        materialize_target(TARGET, dest, props)
        resources = dest / "src/main/resources"
        java = dest / "src/main/java"
        classes = sorted(java.rglob("*.java"))
        require(len(classes) > 2000, f"Fabric materializer lost gameplay sources: {len(classes)}")
        for path in classes:
            content = path.read_text(encoding="utf-8")
            require("net.neoforged." not in content and "net.minecraftforge." not in content,
                    f"Fabric source has a forbidden loader dependency: {path.relative_to(dest)}")
        # This class must be generated per target by Gradle rather than frozen in a port snapshot.
        require(not (java / "buildcraft/lib/net/BuildCraftTarget.java").exists(),
                "Baked-in BuildCraftTarget.java would conflict with generated metadata")
        metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
        require(metadata.get("id") == "buildcraftlib", "Fabric mod ID drift")
        require(metadata.get("version") == "${version}", "Fabric mod version is not tied to Gradle")
        require(metadata.get("accessWidener") == "buildcraft.accesswidener", "Fabric access widener drift")
        require(metadata.get("depends", {}).get("team_reborn_energy") == ">=5.0.0", "Missing required Fabric Energy dependency")
        require((resources / "buildcraft.accesswidener").is_file(), "Fabric access widener is not packaged")
        require((resources / "buildcraft.fabric263.mixins.json").is_file(), "Fabric mixin config is not packaged")
        require((resources / "pack.mcmeta").is_file(), "Fabric datapack metadata missing")
        for entrypoint in metadata["entrypoints"]["main"] + metadata["entrypoints"]["client"]:
            relative = Path(*entrypoint.split(".")).with_suffix(".java")
            require((java / relative).is_file(), f"Fabric entrypoint {entrypoint} missing")
        gt = dest / "src/gametest"
        tests = json.loads((gt / "resources/fabric.mod.json").read_text(encoding="utf-8"))
        require(tests.get("id") == "buildcraft-fabric-gametest", "Fabric server GameTest mod metadata missing")
        require(tests.get("entrypoints", {}).get("fabric-gametest"), "Fabric server GameTests not registered")
        test_source = gt / "java/buildcraft/gametest/FabricInfrastructureGameTests.java"
        require(test_source.is_file() and "@GameTest" in test_source.read_text(encoding="utf-8"),
                "No executable world-backed Fabric GameTest")
        print(f"26.3-fabric source materialized: {len(classes)} Java classes, {len(excludes)} loader-only exclusions")

    print("PASS: 26.3-fabric is wired to Stonecutter, Loom, GameTests, installed server CI, IT16 and publication")


if __name__ == "__main__":
    try:
        validate()
    except (ValueError, FileNotFoundError, KeyError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
