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

from source_layout import (ROOT, _materialize_text_file, effective_source_files, load_properties,
                           materialize_target, target_ids, target_layout)

TARGET = "26.3-fabric"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def contains(path: str, *needles: str) -> None:
    data = (ROOT / path).read_text(encoding="utf-8")
    for needle in needles:
        require(needle in data, f"{path}: missing {needle!r}")


def validate_gametest_parity(fabric: Path, neoforge: Path) -> None:
    """Keep the native Fabric registry in sync with the canonical gameplay suite."""
    expected = {}
    neo_sources = sorted((neoforge / "java").rglob("*.java"))
    for path in neo_sources:
        for raw in re.findall(r"// bc-gametest-v2: (\{[^\n]+\})", path.read_text(encoding="utf-8")):
            marker = json.loads(raw)
            template = marker["template"]
            if template in ("PipeGameTestSupport.LARGE_EMPTY_TEMPLATE",
                            "buildcraft.gametest.PipeGameTestSupport.LARGE_EMPTY_TEMPLATE"):
                template = '"empty7x3x7"'
            require(template in ('"empty3x3x3"', '"empty7x3x7"'),
                    f"Unknown NeoForge GameTest template: {template}")
            require(marker["template_namespace"] == "buildcraft.lib.BCLib.MODID",
                    "Unknown NeoForge GameTest namespace")
            key = (marker["owner"], marker["method"])
            require(key not in expected, f"Duplicate NeoForge GameTest: {key}")
            expected[key] = ("buildcraftlib:" + template.strip('"'), marker["timeout"])
    require(expected, "Canonical NeoForge GameTest suite is missing")
    registry = fabric / "java/buildcraft/gametest/FabricParityGameTests.java"
    source = registry.read_text(encoding="utf-8")
    actual = {}
    pattern = (r'@GameTest\(structure = "([^"]+)", maxTicks = (\d+)\)\s+'
               r'public void (\w+)\(GameTestHelper helper\) throws Exception \{\s+'
               r'([\w.]+)\.(\w+)\(helper\);\s+\}')
    for structure, timeout, wrapper, owner, method in re.findall(pattern, source):
        key = (owner, method)
        require(wrapper == method and key not in actual, f"Invalid/duplicate Fabric GameTest: {key}")
        body = (fabric / "java" / Path(*owner.split(".")).with_suffix(".java")).read_text(encoding="utf-8")
        require(re.search(r"public static void " + re.escape(method) + r"\(GameTestHelper ", body) is not None,
                f"Missing Fabric test implementation: {key}")
        actual[key] = (structure, int(timeout))
    require(source.count("@GameTest(") == len(actual), "Unrecognized Fabric GameTest registration")
    require(actual.keys() == expected.keys(),
            f"GameTest registration drift: missing={sorted(expected.keys() - actual.keys())}, "
            f"extra={sorted(actual.keys() - expected.keys())}")
    for key in expected:
        require(actual[key] == expected[key], f"GameTest structure/timeout drift: {key}")
    metadata = json.loads((fabric / "resources/fabric.mod.json").read_text(encoding="utf-8"))
    require("buildcraft.gametest.FabricParityGameTests" in metadata["entrypoints"]["fabric-gametest"],
            "Fabric parity registry is not a GameTest entrypoint")
    sources = sorted((fabric / "java").rglob("*.java"))
    for path in sources:
        text = path.read_text(encoding="utf-8")
        require(not re.search(r"(?:net\.neoforged|net\.minecraftforge)\.", text),
                f"Fabric GameTest contains a foreign loader reference: {path.name}")
    def traces(paths):
        return sorted(name for path in paths for name in re.findall(
            r'SystemTrace\.emit\("([^"\n]+)"', path.read_text(encoding="utf-8")))
    require(traces(sources) == traces(neo_sources) and len(traces(sources)) == 4,
            "Fabric SystemTrace scenarios diverge from NeoForge")
    for template in {structure.split(":")[1] for structure, _ in expected.values()}:
        require((fabric / f"resources/data/buildcraftlib/gametest/structure/{template}.snbt").is_file(),
                f"Missing Fabric GameTest fixture: {template}")
    print(f"PASS: Fabric registers all {len(expected)} NeoForge GameTests and all four SystemTrace scenarios")


def validate(target: str = "26.3-fabric") -> None:
    global TARGET
    TARGET = target
    props = load_properties()
    require(TARGET in target_ids(props), f"{TARGET} is not registered in the canonical matrix")
    layout = target_layout(TARGET, props)
    require(layout.family == "26.X" and layout.platform == "fabric", f"{TARGET} has the wrong source family/platform")
    for key in ("fabric_loader", "fabric_api", "fabric_installer", "energy", "jei", "jade"):
        require(props.get(f"target.{TARGET}.deps.{key}"), f"Missing pinned Fabric dependency {key}")
    require(props.get(f"target.{TARGET}.java.version") == "25", f"{TARGET} must use JDK 25")
    require(props.get(f"target.{TARGET}.network.protocol") == props.get(f"target.{TARGET.split('-')[0]}-neoforge.network.protocol"),
            f"Network protocol diverges between loaders for {TARGET}")

    # An unanchored 'config/' rule ignores Java packages named config at any
    # depth. This once left Fabric's native config adapters untracked while
    # local ZIP builds still contained them.
    ignore_rules = {
        line.strip() for line in (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    require("/config/" in ignore_rules and not any(
        rule in ignore_rules for rule in ("config/", "**/config/", "*/config/")
    ), "Do not ignore Java config source packages: use a root-anchored /config/ gitignore rule")
    fabric_config = ROOT / "source-family-platforms/26.X/fabric/src/main/java/buildcraft/lib/platform/config"
    for filename in ("ConfigScreenRegistration.java",):
        require((fabric_config / filename).is_file(),
                f"Missing tracked Fabric config adapter: {fabric_config.relative_to(ROOT) / filename}")

    manifest = ROOT / props[f"target.{TARGET}.source.exclusion_manifest"]
    require(manifest.is_file(), "Missing Fabric-only exclusion manifest")
    excludes = [line for line in manifest.read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.startswith("#")]
    require(len(excludes) == len(set(excludes)) and len(excludes) > 0,
            "Fabric exclusion manifest is empty/duplicated")
    require(not any(".." in Path(line).parts or line.startswith("/") for line in excludes),
            "Fabric exclusion manifest contains a path escape")

    contains("builds/26.X/build.fabric.gradle", "net.fabricmc.fabric-loom", "loaders/fabric-target.gradle")
    fabric_build_script = (ROOT / "builds/26.X/build.fabric.gradle").read_text(encoding="utf-8")
    loom_plugin = re.search(r"id\s+['\"]net\.fabricmc\.fabric-loom['\"]\s+version\s+['\"]([^'\"]+)['\"]",
                            fabric_build_script)
    require(loom_plugin is not None and re.fullmatch(r"\d+\.\d+\.\d+", loom_plugin.group(1)) is not None,
            "Fabric Loom must use a published stable x.y.z version (not a SNAPSHOT) in build.fabric.gradle")
    # The published plugin has both a Gradle marker and a direct implementation
    # module. Resolve the latter explicitly to avoid marker lookup failures.
    settings = (ROOT / "builds/26.X/settings.gradle.kts").read_text(encoding="utf-8")
    for contract in ("https://maven.fabricmc.net/", "https://fabricmc.forgecdn.net/",
                     "https://maven.modmuss50.me/",
                     'requested.id.id == "net.fabricmc.fabric-loom"',
                     'useModule("net.fabricmc:fabric-loom:$version")'):
        require(contract in settings, f"Missing Fabric Loom plugin resolution contract: {contract}")
    require('val version = requested.version' in settings,
            "Fabric Loom implementation version must follow the plugin DSL request")
    # Plugin resolution must see the official publishing repository before
    # unrelated Gradle/NeoForge repositories. A later CDN/mirror is fallback.
    require(settings.index('maven("https://maven.fabricmc.net/")') < settings.index('mavenCentral()'),
            "Fabric Maven must precede generic plugin repositories")
    require(settings.index('maven("https://maven.fabricmc.net/")') < settings.index('maven("https://fabricmc.forgecdn.net/")')
            < settings.index('maven("https://maven.modmuss50.me/")'),
            "Fabric plugin repositories must prefer official Maven before CDN/mirror")
    contains("build-logic/common-target.gradle", "targetAccessWidener", "prepareEffectiveSource")
    contains("build-logic/loaders/fabric-target.gradle",
             "accessWidenerPath", "configureTests", "runGameTest", "fabricDistributionMods",
             "runtimeOnly requiredProperty('deps.jei')", "runtimeOnly requiredProperty('deps.jade')",
             "collectFabricServerMods", "prepareEffectiveSource", "buildAndCollect")
    # Shared JUnit tests contain Jupiter parameterized sources; Fabric must
    # carry the same test compile/runtime modules as Forge and NeoForge.
    contains("build-logic/loaders/fabric-target.gradle",
             "testImplementation \"org.junit.jupiter:junit-jupiter-api:${requiredProperty('deps.junit')}\"",
             "testImplementation \"org.junit.jupiter:junit-jupiter-params:${requiredProperty('deps.junit')}\"",
             "testRuntimeOnly \"org.junit.jupiter:junit-jupiter-engine:${requiredProperty('deps.junit')}\"",
             "testRuntimeOnly platform(\"org.junit:junit-bom:${requiredProperty('deps.junit')}\")",
             "testRuntimeOnly 'org.junit.platform:junit-platform-launcher'",
             "useJUnitPlatform()")
    # Plain JVM tests must run against the effective (materialized) source tree,
    # with resource metadata resolved from BCCE's shipped JAR and initialized
    # Minecraft registries for native Fabric fluid tests.
    contains("build-logic/loaders/fabric-target.gradle",
             "workingDir effectiveSourceDir",
             "systemProperty 'buildcraft.expectedPackFormat'",
             "classpath = files(packagedModJar.flatMap { it.archiveFile }) + classpath",
             "def zip = new java.util.zip.ZipFile(archive)")
    contains("source-family-platforms/26.X/fabric/src/test/java/buildcraft/lib/fluid/FabricTankStateTest.java",
             "@BeforeAll", "SharedConstants.tryDetectVersion();", "Bootstrap.bootStrap();")
    contains("scripts/ci-server-smoke.sh", "fabric-installer", "fabric-server-launch.jar", "collectFabricServerMods")
    contains("scripts/ci-modpack-world-join.py", f'"{TARGET}": "26.X"', "ci_world_join")
    contains("scripts/run-ci-local.py", f'("{TARGET}", "26.X", 25)')
    contains(".github/workflows/ci.yml", f"- target: {TARGET}", "Collect installed Fabric server dependencies")
    contains(".github/workflows/publish.yml", TARGET, f"Publish {TARGET.split('-')[0]} Fabric", "loaders: fabric")

    lock = json.loads((ROOT / "build-config/modpack-world-join-lock.json").read_text(encoding="utf-8"))
    require(TARGET in lock.get("targets", {}), f"{TARGET} is absent from the locked client/server test")
    require(lock["targets"][TARGET]["generation"] == "26.X", "Invalid Fabric world-join generation")

    with tempfile.TemporaryDirectory(prefix=f"buildcraft-{TARGET}-") as tmp:
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
        # Loader-free imports alone are insufficient: internal compatibility types
        # must also be selected by the materializer for the older Fabric target.
        source_roots = [java, dest / "src/test/java", dest / "src/gametest/java",
                        dest / "addon-fixture/src/main/java"]
        internal_import = re.compile(r"^import (buildcraft\.[\w.]+);", re.MULTILINE)
        for source_root in source_roots:
            for path in source_root.rglob("*.java"):
                content = path.read_text(encoding="utf-8")
                require("net.neoforged." not in content and "net.minecraftforge." not in content,
                        f"Fabric source has a forbidden loader dependency: {path.relative_to(dest)}")
                for name in internal_import.findall(content):
                    if (".compat.neoforge263." not in name
                            and not name.endswith(("Container263", ".FluidAction"))):
                        continue
                    parts = name.split(".")
                    require(any((java / Path(*parts[:end]).with_suffix(".java")).is_file()
                                for end in range(len(parts), 2, -1)),
                            f"Missing Fabric internal contract {name}: {path.relative_to(dest)}")
        for name in ("lib/internal/mj/IMjReceiver.java", "lib/internal/mj/MjBattery.java"):
            content = (java / "buildcraft" / name).read_text(encoding="utf-8")
            require("Enum<?> action" in content and
                    "buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction" in content,
                    f"Fabric MJ action bridge missing: {name}")
        if TARGET == "26.2-fabric":
            for name, old, forbidden in (
                ("builders/client/render/pip/BlueprintPipRenderState.java",
                 "getBounds(x0, y0, x1, y1, scissorArea)", "getBounds(x0, y0, x1, y1, pose,"),
                ("robotics/client/render/preview/RobotPreviewSubmission.java",
                 "getBounds(x0, y0, x1, y1, null)", "getBounds(x0, y0, x1, y1, pose,"),
                ("transport/block/BlockPipeHolder.java",
                 "spawnDestroyParticles(world, player, pos, state)", "spawnDestroyParticles(world, pos, state)"),
                ("builders/snapshot/FakeWorld.java", "return superLevel.fuelValues();", ""),
            ):
                content = (java / "buildcraft" / name).read_text(encoding="utf-8")
                require(old in content and (not forbidden or forbidden not in content),
                        f"26.2 Fabric vanilla downport missing: {name}")
        # Dedicated-server class loading must not resolve client-only types from
        # the Builders item registry or entity schematic factory. This is a
        # verified runGameTest startup regression on Fabric 26.3.
        items = (java / "buildcraft/builders/BCBuildersItems.java").read_text(encoding="utf-8")
        model_properties = (java / "buildcraft/builders/client/FabricItemModelProperties.java").read_text(encoding="utf-8")
        builders_client = (java / "buildcraft/builders/client/FabricBuildersClient.java").read_text(encoding="utf-8")
        require("net.minecraft.client." not in items and "ClientModelProperties" not in items,
                "Builders item registry must not link Minecraft client rendering types")
        require('"used"' in model_properties and '"recording"' in model_properties,
                "Client-only Builders item model properties were lost")
        require("FabricItemModelProperties.register(" in builders_client,
                "Fabric client initializer must register Builders item model properties")
        for item_class in ("ItemSnapshot", "ItemSchematicSingle"):
            item_src = (java / f"buildcraft/builders/item/{item_class}.java").read_text(encoding="utf-8")
            require(re.search(r"@Environment\(EnvType\.CLIENT\)\s+public void appendHoverText\(", item_src),
                    f"{item_class}: client tooltip override must be stripped on dedicated servers")
        entity_schematic = (java / "buildcraft/builders/snapshot/SchematicEntityDefault.java").read_text(encoding="utf-8")
        preview_world = (java / "buildcraft/builders/snapshot/FakeWorld.java").read_text(encoding="utf-8")
        require("instanceof FakeWorld" not in entity_schematic
                and "instanceof BlueprintPreviewEntitySink" in entity_schematic,
                "Server-registered entity schematic must not load client-only FakeWorld")
        require("interface BlueprintPreviewEntitySink" in entity_schematic
                and "implements BlueprintPreviewEntitySink" in preview_world,
                "Blueprint preview world must implement the server-safe entity sink")
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
        # Fabric Transfer API's FluidVariantCache is added to Minecraft Fluid by a mixin.
        # Two native storage contracts MUST run under real Fabric Loader, not the
        # unmixed plain-JUnit JVM (which throws ClassCastException on FluidVariant.of).
        native_tests = gt / "java/buildcraft/gametest/FabricTankStorageGameTests.java"
        require("buildcraft.gametest.FabricTankStorageGameTests" in
                tests["entrypoints"]["fabric-gametest"],
                "Native Fabric tank GameTests must be registered with the loader")
        require(native_tests.is_file(), "Native Fabric tank GameTest source missing")
        native_source = native_tests.read_text(encoding="utf-8")
        for name in ("nativeSingleTankStorageUsesTheCallerTransactionAndDropletUnits",
                     "nativeManagerStorageKeepsDistinctFillAndDrainPriorities"):
            require(re.search(r"@GameTest\s+public void " + name + r"\(GameTestHelper helper\)",
                              native_source) is not None,
                    f"Native Fabric storage contract not runnable as GameTest: {name}")
            require(name not in (dest / "src/test/java/buildcraft/lib/fluid/FabricTankStateTest.java")
                    .read_text(encoding="utf-8"),
                    f"Native Fabric storage contract still runs without loader mixins: {name}")
        require(native_source.count("helper.succeed();") == 2,
                "Each native Fabric tank GameTest must explicitly report success")
        neoforge = Path(tmp) / "neoforge"
        neo_target = TARGET.replace("-fabric", "-neoforge")
        neo_layout = target_layout(neo_target, props)
        # Only GameTest sources are needed for registration parity, not another
        # full copy of the entire production tree.
        for relative, source in effective_source_files(neo_layout, props, "src/gametest/java").items():
            _materialize_text_file(source, neoforge / relative, logical_relative=relative,
                                   minecraft=props[f"target.{neo_target}.deps.minecraft"],
                                   family=neo_layout.family, platform=neo_layout.platform,
                                   preprocess=True)
        validate_gametest_parity(gt, neoforge / "src/gametest")
        print(f"{TARGET} source materialized: {len(classes)} Java classes, {len(excludes)} loader-only exclusions")

    print(f"PASS: {TARGET} is wired to Stonecutter, Loom, GameTests, installed server CI, IT16 and publication")


if __name__ == "__main__":
    try:
        for target in ("26.3-fabric", "26.2-fabric"):
            validate(target)
    except (ValueError, FileNotFoundError, KeyError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
