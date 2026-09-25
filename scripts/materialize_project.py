#!/usr/bin/env python3
"""Export one BuildCraft target as a conventional standalone Gradle project.

The maintained repository remains the source of truth.  This exporter resolves the
layered source tree with ``materialize_target()``, applies the target's compile-time
source exclusions, bakes target metadata into resources/BuildCraftTarget, and writes
a normal single-target Forge, NeoForge or Fabric Gradle project that no longer depends on
Stonecutter, build-config, build-logic, or the source materializer.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from source_config import ROOT, load_properties, target_build_root, target_ids, target_layout
from source_layout import materialize_target


DATAGEN_DISABLED_SOURCES = (
    "src/main/java/buildcraft/energy/BCEnergyProvider.java",
    "src/main/java/buildcraft/lib/BCTagsProvider.java",
    "src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "src/main/java/buildcraft/factory/BCFactoryRecipesProvider.java",
    "src/main/java/buildcraft/transport/BCTransportRecipesProvider.java",
    "src/main/java/buildcraft/core/BCCoreRecipes.java",
    "src/main/java/buildcraft/core/client/model/FragileFluidContainerModel.java",
    "src/main/java/buildcraft/core/client/model/ModelEngine.java",
)

COMPAT_NAMES = ("jei", "jade", "ic2", "forestry")


def _target_property(properties: dict[str, str], target: str, key: str, default: str = "") -> str:
    value = properties.get(f"target.{target}.{key}")
    if value is not None:
        return value.strip()
    value = properties.get(f"common.{key}")
    return value.strip() if value is not None else default


def _bool_property(properties: dict[str, str], target: str, key: str, fallback: bool) -> bool:
    raw = _target_property(properties, target, key, "")
    if not raw:
        return fallback
    return raw.lower() in {"1", "true", "yes", "on"}


def _compat_enabled(properties: dict[str, str], target: str, name: str) -> bool:
    explicit = _target_property(properties, target, f"compat.{name}.enabled", "")
    if explicit:
        return explicit.lower() in {"1", "true", "yes", "on"}
    return bool(_target_property(properties, target, f"deps.{name}", ""))


def _groovy(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def _project_version(properties: dict[str, str], target: str) -> str:
    mod_version = _target_property(properties, target, "mod.version")
    return f"{mod_version}+{target.replace('-', '+')}"


def _git_value(args: list[str], fallback: str = "unknown") -> str:
    try:
        completed = subprocess.run(
            ["git", *args],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return fallback
    value = (completed.stdout or "").strip()
    return value if completed.returncode == 0 and value else fallback


def _write_buildcraft_target(project_root: Path, properties: dict[str, str], target: str) -> None:
    minecraft = _target_property(properties, target, "deps.minecraft")
    loader = _target_property(properties, target, "source.platform")
    protocol = _target_property(properties, target, "network.protocol")
    version = _project_version(properties, target)
    branch = _git_value(["rev-parse", "--abbrev-ref", "HEAD"])
    commit_hash = _git_value(["rev-parse", "HEAD"])
    commit_message = _git_value(["log", "-1", "--pretty=%s"])
    commit_author = _git_value(["log", "-1", "--pretty=%an <%ae>"])

    def java_literal(value: str) -> str:
        return json.dumps(value, ensure_ascii=False)

    output = project_root / "src/main/java/buildcraft/lib/net/BuildCraftTarget.java"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "package buildcraft.lib.net;\n\n"
        "/** Target metadata baked into this standalone project by the BCCE materializer. */\n"
        "public final class BuildCraftTarget {\n"
        f"    public static final String MINECRAFT_VERSION = {java_literal(minecraft)};\n"
        f"    public static final String LOADER = {java_literal(loader)};\n"
        f"    public static final String NETWORK_PROTOCOL = {java_literal(protocol)};\n"
        f"    public static final String MOD_VERSION = {java_literal(version)};\n"
        f"    public static final String GIT_BRANCH = {java_literal(branch)};\n"
        f"    public static final String GIT_COMMIT_HASH = {java_literal(commit_hash)};\n"
        f"    public static final String GIT_COMMIT_MESSAGE = {java_literal(commit_message)};\n"
        f"    public static final String GIT_COMMIT_AUTHOR = {java_literal(commit_author)};\n\n"
        "    private BuildCraftTarget() {}\n"
        "}\n",
        encoding="utf-8",
        newline="",
    )


def _resource_values(properties: dict[str, str], target: str) -> dict[str, str]:
    platform = _target_property(properties, target, "source.platform")
    values = {
        "mod_version": _project_version(properties, target),
        "mod_authors": _target_property(properties, target, "mod.authors"),
        "mod_license": _target_property(properties, target, "mod.license"),
        "loader_version_range": _target_property(properties, target, "loader.version_range"),
        "minecraft_version_range": _target_property(properties, target, "minecraft.version_range"),
        "buildcraft_version_range": _target_property(properties, target, "buildcraft.version_range"),
        "jei_version_range": _target_property(properties, target, "compat.jei.range"),
        "jade_version_range": _target_property(properties, target, "compat.jade.range"),
        "ic2_version_range": _target_property(properties, target, "compat.ic2.range"),
        "forestry_version_range": _target_property(properties, target, "compat.forestry.range"),
        "pack_format": _target_property(properties, target, "pack.format"),
        "resource_pack_format": _target_property(properties, target, "pack.resource_format"),
        "data_pack_format": _target_property(properties, target, "pack.data_format"),
    }
    if platform == "forge":
        values["forge_version_range"] = _target_property(properties, target, "forge.version_range")
    elif platform == "neoforge":
        values["neo_version_range"] = _target_property(properties, target, "neoforge.version_range")
    elif platform == "fabric":
        values["fabric_api_version_range"] = _target_property(properties, target, "fabric_api.version_range")
    return values


def _expand_target_resources(project_root: Path, properties: dict[str, str], target: str) -> None:
    platform = _target_property(properties, target, "source.platform")
    metadata = (
        project_root / "src/main/resources/META-INF/mods.toml"
        if platform == "forge"
        else project_root / "src/main/resources/META-INF/neoforge.mods.toml"
        if platform == "neoforge"
        else project_root / "src/main/resources/fabric.mod.json"
        if platform == "fabric"
        else None
    )
    if metadata is None:
        raise ValueError(f"{target}: unsupported metadata loader {platform!r}")
    files = [metadata, project_root / "src/main/resources/pack.mcmeta"]
    values = _resource_values(properties, target)
    for path in files:
        if not path.is_file():
            raise ValueError(f"{target}: required materialized resource is missing: {path.relative_to(project_root)}")
        text = path.read_text(encoding="utf-8")
        for key, value in values.items():
            text = text.replace("${" + key + "}", value)
        unresolved = sorted({part.split("}", 1)[0] for part in text.split("${")[1:] if "}" in part})
        if unresolved:
            raise ValueError(
                f"{target}: unresolved build placeholder(s) in {path.relative_to(project_root)}: {', '.join(unresolved)}"
            )
        path.write_text(text, encoding="utf-8", newline="")


def _apply_compile_exclusions(project_root: Path, properties: dict[str, str], target: str) -> None:
    java_root = project_root / "src/main/java"
    for name in COMPAT_NAMES:
        if not _compat_enabled(properties, target, name):
            shutil.rmtree(java_root / "buildcraft/compat" / name, ignore_errors=True)

    if not _bool_property(properties, target, "compile.datagen.enabled", True):
        for relative in DATAGEN_DISABLED_SOURCES:
            (project_root / relative).unlink(missing_ok=True)


def _settings_gradle(platform: str, project_name: str) -> str:
    loader_repo = (
        "        maven { url = 'https://maven.minecraftforge.net/' }\n"
        if platform == "forge"
        else "        maven { url = 'https://maven.neoforged.net/releases' }\n"
        if platform == "neoforge"
        else "        maven { url = 'https://maven.fabricmc.net/' }\n"
        if platform == "fabric"
        else ""
    )
    return (
        "pluginManagement {\n"
        "    repositories {\n"
        "        gradlePluginPortal()\n"
        "        mavenCentral()\n"
        f"{loader_repo}"
        "    }\n"
        "}\n\n"
        "plugins {\n"
        "    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'\n"
        "}\n\n"
        f"rootProject.name = {_groovy(project_name)}\n"
    )


def _common_gradle_header(properties: dict[str, str], target: str, *, neo: bool) -> str:
    plugins = (
        "    id 'java-library'\n"
        if neo
        else "    id 'java'\n"
    )
    loader_plugin = (
        "    id 'net.neoforged.moddev' version '2.0.143'\n"
        if neo
        else "    id 'net.minecraftforge.gradle' version '[6.0,6.2)'\n"
    )
    return (
        "plugins {\n"
        f"{plugins}"
        "    id 'idea'\n"
        "    id 'eclipse'\n"
        "    id 'maven-publish'\n"
        f"{loader_plugin}"
        "}\n\n"
        f"version = {_groovy(_project_version(properties, target))}\n"
        f"group = {_groovy(_target_property(properties, target, 'mod.group'))}\n"
        f"base.archivesName = {_groovy(_target_property(properties, target, 'mod.archive_name'))}\n\n"
        "java {\n"
        f"    toolchain.languageVersion = JavaLanguageVersion.of({_target_property(properties, target, 'java.version')})\n"
        "}\n\n"
        "def gameTestRunRequested = gradle.startParameter.taskNames.any {\n"
        "    it.toLowerCase().contains('rungametestserver')\n"
        "}\n\n"
        "sourceSets {\n"
        "    gameTest {\n"
        "        java.srcDir 'src/gametest/java'\n"
        "        resources.srcDir 'src/gametest/resources'\n"
        "        compileClasspath += sourceSets.main.output + sourceSets.main.compileClasspath\n"
        "        runtimeClasspath += output + sourceSets.main.output\n"
        "    }\n"
        "}\n"
        "if (gameTestRunRequested) {\n"
        "    sourceSets.main.java.srcDir 'src/gametest/java'\n"
        "    sourceSets.main.resources.setSrcDirs(['src/gametest/resources', 'src/main/resources'])\n"
        "}\n\n"
        "configurations {\n"
        "    gameTestImplementation.extendsFrom implementation\n"
        "    gameTestRuntimeOnly.extendsFrom runtimeOnly\n"
        + ("    runtimeClasspath.extendsFrom localRuntime\n" if neo else "")
        + "}\n\n"
    )


def _forge_build_gradle(properties: dict[str, str], target: str) -> str:
    minecraft = _target_property(properties, target, "deps.minecraft")
    forge = _target_property(properties, target, "deps.forge")
    mod_id = _target_property(properties, target, "mod.id")
    pack_format = _target_property(properties, target, "pack.format")
    deps: list[str] = [f'    minecraft "net.minecraftforge:forge:{minecraft}-{forge}"']

    for name in ("jei", "jade", "ic2", "forestry"):
        dep = _target_property(properties, target, f"deps.{name}")
        if dep and _compat_enabled(properties, target, name):
            deps.append(f"    compileOnly fg.deobf({_groovy(dep)})")
            deps.append(f"    runtimeOnly fg.deobf({_groovy(dep)})")
            if name == "ic2":
                carbon = _target_property(properties, target, "deps.carbon_config")
                if carbon:
                    deps.append(f"    compileOnly fg.deobf({_groovy(carbon)})")
                    deps.append(f"    runtimeOnly fg.deobf({_groovy(carbon)})")
    patchouli = _target_property(properties, target, "deps.patchouli")
    if patchouli and _compat_enabled(properties, target, "forestry"):
        deps.append(f"    compileOnly fg.deobf({_groovy(patchouli)})")
        deps.append(f"    runtimeOnly fg.deobf({_groovy(patchouli)})")

    junit = _target_property(properties, target, "deps.junit")
    deps.extend(
        [
            f'    testImplementation "org.junit.jupiter:junit-jupiter-api:{junit}"',
            f'    testImplementation "org.junit.jupiter:junit-jupiter-params:{junit}"',
            f'    testRuntimeOnly "org.junit.jupiter:junit-jupiter-engine:{junit}"',
        ]
    )

    return _common_gradle_header(properties, target, neo=False) + f"""def minecraftVersion = {_groovy(minecraft)}

def runDirectory = file('run')
def dataRunDirectory = file('run-data')
def generatedResources = layout.buildDirectory.dir('generated-resources/datagen').get().asFile

minecraft {{
    mappings channel: 'official', version: minecraftVersion
    def at = file('src/main/resources/META-INF/accesstransformer.cfg')
    if (at.isFile()) {{
        accessTransformer = at
    }}

    runs {{
        configureEach {{
            workingDirectory runDirectory
            property 'forge.logging.markers', 'REGISTRIES'
            property 'forge.logging.console.level', 'debug'
            property 'forge.enabledGameTestNamespaces', 'forge,buildcraftlib'
            property 'mixin.env.remapRefMap', 'true'
            property 'mixin.env.refMapRemappingFile', file('build/createSrgToMcp/output.srg').absolutePath
            mods {{
                buildcraft {{ source sourceSets.main }}
            }}
        }}
        client {{ }}
        server {{ args '--nogui' }}
        gameTestServer {{ }}
        data {{
            workingDirectory dataRunDirectory
            args '--mod', {_groovy(mod_id)}, '--all', '--output', generatedResources,
                    '--existing', file('src/main/resources')
        }}
    }}
}}

tasks.matching {{ it.name == 'runData' }}.configureEach {{
    doFirst {{
        project.delete(generatedResources)
        generatedResources.mkdirs()
    }}
}}

repositories {{
    exclusiveContent {{
        forRepository {{
            maven {{
                name = 'Modrinth'
                url = 'https://api.modrinth.com/maven'
            }}
        }}
        forRepositories(fg.repository)
        filter {{ includeGroup 'maven.modrinth' }}
    }}
    maven {{
        name = 'Sponge'
        url = 'https://repo.spongepowered.org/repository/maven-public/'
    }}
    mavenCentral()
}}

dependencies {{
{chr(10).join(deps)}
}}

afterEvaluate {{
    def reobfTask = tasks.findByName('reobfJar')
    if (reobfTask != null) {{
        tasks.getByName('jar').finalizedBy(reobfTask)
    }}
}}

tasks.named('test', Test) {{
    useJUnitPlatform()
    workingDir projectDir
    systemProperty 'buildcraft.expectedPackFormat', {_groovy(pack_format)}
    testLogging {{
        events 'failed', 'skipped'
        exceptionFormat 'full'
    }}
}}

tasks.named('processResources', ProcessResources) {{
    if (gameTestRunRequested) {{
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }}
}}

tasks.named('jar', Jar) {{
    archiveClassifier = ''
    manifest {{
        attributes([
                'Specification-Title': {_groovy(_target_property(properties, target, 'mod.name'))},
                'Specification-Vendor': {_groovy(_target_property(properties, target, 'mod.authors'))},
                'Specification-Version': {_groovy(_target_property(properties, target, 'mod.version'))},
                'Implementation-Title': {_groovy(_target_property(properties, target, 'mod.name'))},
                'Implementation-Version': project.version,
                'Implementation-Vendor': {_groovy(_target_property(properties, target, 'mod.authors'))}
        ])
    }}
}}

tasks.withType(JavaCompile).configureEach {{
    options.encoding = 'UTF-8'
    options.compilerArgs.add('-Xlint:none')
}}

publishing {{
    publications {{
        register('mavenJava', MavenPublication) {{ artifact jar }}
    }}
}}

idea.module {{
    downloadJavadoc = true
    downloadSources = true
}}
eclipse.classpath {{
    downloadJavadoc = true
    downloadSources = true
}}
"""


def _neoforge_build_gradle(properties: dict[str, str], target: str) -> str:
    neoforge = _target_property(properties, target, "deps.neoforge")
    pack_format = _target_property(properties, target, "pack.format")
    deps: list[str] = []
    for name in ("jei", "jade"):
        dep = _target_property(properties, target, f"deps.{name}")
        if dep and _compat_enabled(properties, target, name):
            deps.append(f"    compileOnly {_groovy(dep)}")
            deps.append(f"    localRuntime {_groovy(dep)}")
    junit = _target_property(properties, target, "deps.junit")
    deps.extend(
        [
            f'    testImplementation "org.junit.jupiter:junit-jupiter-api:{junit}"',
            f'    testImplementation "org.junit.jupiter:junit-jupiter-params:{junit}"',
            f'    testRuntimeOnly "org.junit.jupiter:junit-jupiter-engine:{junit}"',
        ]
    )

    return _common_gradle_header(properties, target, neo=True) + f"""def runDirectory = file('run')
def dataRunDirectory = file('run-data')
def generatedResources = layout.buildDirectory.dir('generated-resources/datagen').get().asFile

def buildcraftModIds = [
        'buildcraftlib', 'buildcraftcore', 'buildcraftfactory', 'buildcrafttransport',
        'buildcraftenergy', 'buildcraftbuilders', 'buildcraftsilicon', 'buildcraftrobotics'
]

neoForge {{
    version = {_groovy(neoforge)}
    addModdingDependenciesTo sourceSets.test
    addModdingDependenciesTo sourceSets.gameTest

    def at = file('src/main/resources/META-INF/accesstransformer.cfg')
    if (at.isFile()) {{
        accessTransformers.from(at)
    }}

    runs {{
        client {{
            client()
            gameDirectory = runDirectory
            systemProperty 'neoforge.enabledGameTestNamespaces', 'buildcraftlib'
        }}
        server {{
            server()
            gameDirectory = runDirectory
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', 'buildcraftlib'
        }}
        gameTestServer {{
            type = 'gameTestServer'
            sourceSet = sourceSets.main
            gameDirectory = runDirectory
            systemProperty 'neoforge.enabledGameTestNamespaces', 'buildcraftlib'
        }}
        data {{
            data()
            gameDirectory = dataRunDirectory
            programArguments.addAll(
                    '--mod', buildcraftModIds.join(','), '--all',
                    '--output', generatedResources.absolutePath,
                    '--existing', file('src/main/resources').absolutePath
            )
        }}
        configureEach {{ logLevel = org.slf4j.event.Level.INFO }}
    }}

    mods {{
        buildcraftlib {{ sourceSet(sourceSets.main) }}
    }}
}}

tasks.matching {{ it.name == 'runData' }}.configureEach {{
    doFirst {{
        project.delete(generatedResources)
        generatedResources.mkdirs()
    }}
}}

repositories {{
    mavenCentral()
    exclusiveContent {{
        forRepository {{
            maven {{
                name = 'Modrinth'
                url = 'https://api.modrinth.com/maven'
            }}
        }}
        filter {{ includeGroup 'maven.modrinth' }}
    }}
    maven {{
        name = 'Sponge'
        url = 'https://repo.spongepowered.org/repository/maven-public/'
    }}
}}

dependencies {{
{chr(10).join(deps)}
}}

tasks.named('test', Test) {{
    useJUnitPlatform()
    workingDir projectDir
    systemProperty 'buildcraft.expectedPackFormat', {_groovy(pack_format)}
    testLogging {{
        events 'failed', 'skipped'
        exceptionFormat 'full'
    }}
}}

tasks.named('processResources', ProcessResources) {{
    if (gameTestRunRequested) {{
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }}
}}

tasks.named('jar', Jar) {{
    archiveClassifier = ''
    manifest {{
        attributes([
                'Specification-Title': {_groovy(_target_property(properties, target, 'mod.name'))},
                'Specification-Vendor': {_groovy(_target_property(properties, target, 'mod.authors'))},
                'Specification-Version': {_groovy(_target_property(properties, target, 'mod.version'))},
                'Implementation-Title': {_groovy(_target_property(properties, target, 'mod.name'))},
                'Implementation-Version': project.version,
                'Implementation-Vendor': {_groovy(_target_property(properties, target, 'mod.authors'))}
        ])
    }}
}}

tasks.withType(JavaCompile).configureEach {{
    options.encoding = 'UTF-8'
    options.compilerArgs.add('-Xlint:none')
}}

publishing {{
    publications {{
        register('mavenJava', MavenPublication) {{ artifact jar }}
    }}
}}

idea.module {{
    downloadJavadoc = true
    downloadSources = true
}}
eclipse.classpath {{
    downloadJavadoc = true
    downloadSources = true
}}
"""



def _fabric_build_gradle(properties: dict[str, str], target: str) -> str:
    minecraft = _target_property(properties, target, "deps.minecraft")
    loom_version = _target_property(properties, target, "deps.fabric_loom")
    loader_version = _target_property(properties, target, "deps.fabric_loader")
    api_version = _target_property(properties, target, "deps.fabric_api")
    energy_version = _target_property(properties, target, "deps.energy_api")
    junit = _target_property(properties, target, "deps.junit")
    return f"""plugins {{
    id 'java'
    id 'idea'
    id 'eclipse'
    id 'maven-publish'
    id 'fabric-loom' version {_groovy(loom_version)}
}}

version = {_groovy(_project_version(properties, target))}
group = {_groovy(_target_property(properties, target, 'mod.group'))}
base.archivesName = {_groovy(_target_property(properties, target, 'mod.archive_name'))}

java {{
    toolchain.languageVersion = JavaLanguageVersion.of({_target_property(properties, target, 'java.version')})
}}

sourceSets {{
    main {{
        java.include 'buildcraft/api/v2/**'
        java.include 'buildcraft/fabric/**'
        java.include 'buildcraft/lib/net/BC*.java'
        java.include 'buildcraft/lib/net/Fabric*.java'
        java.include 'buildcraft/lib/net/BuildCraftTarget.java'
        java.include 'buildcraft/lib/internal/module/IBuildCraftMod.java'
        java.include 'buildcraft/lib/internal/module/BCModules.java'
        java.include 'buildcraft/lib/internal/transfer/**'
        java.include 'buildcraft/lib/internal/api/v2/**'
        java.include 'buildcraft/lib/internal/mj/MjApi2PlatformBridge.java'
        java.include 'buildcraft/lib/platform/**'
        resources.include 'fabric.mod.json'
        resources.include 'buildcraft.accesswidener'
        resources.include 'buildcraft.fabric.mixins.json'
        resources.include 'pack.mcmeta'
    }}
}}

repositories {{
    maven {{ url = 'https://maven.fabricmc.net/' }}
    mavenCentral()
}}

dependencies {{
    minecraft {_groovy('com.mojang:minecraft:' + minecraft)}
    mappings loom.officialMojangMappings()
    modImplementation {_groovy('net.fabricmc:fabric-loader:' + loader_version)}
    modImplementation {_groovy('net.fabricmc.fabric-api:fabric-api:' + api_version)}
    modImplementation {_groovy('teamreborn:energy:' + energy_version)}
    include {_groovy('teamreborn:energy:' + energy_version)}
    testImplementation {_groovy('org.junit.jupiter:junit-jupiter-api:' + junit)}
    testImplementation {_groovy('org.junit.jupiter:junit-jupiter-params:' + junit)}
    testRuntimeOnly {_groovy('org.junit.jupiter:junit-jupiter-engine:' + junit)}
}}

loom {{
    accessWidenerPath = file('src/main/resources/buildcraft.accesswidener')
    runs {{
        client {{
            client()
            runDir = file('run').absolutePath
        }}
        server {{
            server()
            runDir = file('run').absolutePath
        }}
    }}
}}

tasks.named('test', Test) {{
    useJUnitPlatform()
}}

tasks.named('processResources', ProcessResources) {{
    filesMatching(['fabric.mod.json', 'pack.mcmeta']) {{
        expand([
                mod_version: project.version.toString(),
                mod_authors: {_groovy(_target_property(properties, target, 'mod.authors'))},
                mod_license: {_groovy(_target_property(properties, target, 'mod.license'))},
                loader_version_range: {_groovy(_target_property(properties, target, 'loader.version_range'))},
                fabric_api_version_range: {_groovy(_target_property(properties, target, 'fabric_api.version_range'))},
                minecraft_version_range: {_groovy(_target_property(properties, target, 'minecraft.version_range'))},
                pack_format: {_groovy(_target_property(properties, target, 'pack.format'))},
                resource_pack_format: {_groovy(_target_property(properties, target, 'pack.resource_format'))},
                data_pack_format: {_groovy(_target_property(properties, target, 'pack.data_format'))}
        ])
    }}
}}

tasks.named('jar', Jar) {{
    archiveClassifier = 'dev'
}}

tasks.named('remapJar') {{
    archiveClassifier = ''
}}

tasks.withType(JavaCompile).configureEach {{
    options.encoding = 'UTF-8'
    options.compilerArgs.add('-Xlint:none')
}}

publishing {{
    publications {{
        register('mavenJava', MavenPublication) {{
            artifact tasks.named('remapJar')
        }}
    }}
}}

idea.module {{
    downloadJavadoc = true
    downloadSources = true
}}
eclipse.classpath {{
    downloadJavadoc = true
    downloadSources = true
}}
"""

def _write_gradle_project(project_root: Path, properties: dict[str, str], target: str) -> None:
    layout = target_layout(target, properties)
    project_name = f"BuildCraft-{target}"
    (project_root / "settings.gradle").write_text(
        _settings_gradle(layout.platform, project_name), encoding="utf-8", newline=""
    )
    build_gradle = (
        _forge_build_gradle(properties, target)
        if layout.platform == "forge"
        else _neoforge_build_gradle(properties, target)
        if layout.platform == "neoforge"
        else _fabric_build_gradle(properties, target)
        if layout.platform == "fabric"
        else None
    )
    if build_gradle is None:
        raise ValueError(f"{target}: standalone Gradle export is not implemented for loader {layout.platform!r}")
    (project_root / "build.gradle").write_text(build_gradle, encoding="utf-8", newline="")

    build_root = target_build_root(target, properties)
    source_props = build_root / "gradle.properties"
    if source_props.is_file():
        shutil.copy2(source_props, project_root / "gradle.properties")
    else:
        (project_root / "gradle.properties").write_text(
            "org.gradle.jvmargs=-Xmx4G -Dfile.encoding=UTF-8\n"
            "org.gradle.daemon=false\n"
            "org.gradle.parallel=true\n"
            "org.gradle.caching=true\n",
            encoding="utf-8",
        )

    for name in ("gradlew", "gradlew.bat"):
        source = build_root / name
        if not source.is_file():
            raise ValueError(f"{target}: Gradle wrapper file is missing: {source}")
        destination = project_root / name
        shutil.copy2(source, destination)
        if name == "gradlew":
            destination.chmod(destination.stat().st_mode | 0o111)
    wrapper = build_root / "gradle/wrapper"
    if not wrapper.is_dir():
        raise ValueError(f"{target}: Gradle wrapper directory is missing: {wrapper}")
    shutil.copytree(wrapper, project_root / "gradle/wrapper", dirs_exist_ok=True)


def _write_root_files(project_root: Path, properties: dict[str, str], target: str) -> None:
    minecraft = _target_property(properties, target, "deps.minecraft")
    loader = _target_property(properties, target, "source.platform")
    java = _target_property(properties, target, "java.version")
    mod_version = _target_property(properties, target, "mod.version")
    loader_name = "NeoForge" if loader == "neoforge" else loader.capitalize()

    license_file = ROOT / "LICENSE.txt"
    if license_file.is_file():
        shutil.copy2(license_file, project_root / "LICENSE.txt")
    attributes = ROOT / ".gitattributes"
    if attributes.is_file():
        shutil.copy2(attributes, project_root / ".gitattributes")

    (project_root / ".gitignore").write_text(
        ".gradle/\n.idea/\n.vscode/\nbuild/\nout/\nrun/\nrun-data/\n*.iml\n",
        encoding="utf-8",
    )
    (project_root / "README.md").write_text(
        f"# BuildCraft Community Edition {mod_version}\n\n"
        f"Standalone source project for **Minecraft {minecraft} {loader_name}**.\n\n"
        f"- Minecraft: `{minecraft}`\n"
        f"- Loader: `{loader_name}`\n"
        f"- Java: `{java}`\n"
        f"- BuildCraft CE: `{mod_version}`\n\n"
        "Build with:\n\n"
        "```text\n"
        "./gradlew build\n"
        "```\n\n"
        "On Windows use `gradlew.bat build`. Client/server/data run tasks are also available through Gradle.\n",
        encoding="utf-8",
        newline="",
    )


def _verify_project(project_root: Path, target: str) -> None:
    required = (
        "src/main/java",
        "src/main/resources",
        "build.gradle",
        "settings.gradle",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.properties",
        "src/main/java/buildcraft/lib/net/BuildCraftTarget.java",
    )
    missing = [relative for relative in required if not (project_root / relative).exists()]
    if missing:
        raise ValueError(f"{target}: incomplete standalone export; missing {', '.join(missing)}")

    leaks: list[str] = []
    for path in (project_root / "src").rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if "//?" in text or "/*?" in text:
            leaks.append(path.relative_to(project_root).as_posix())
            if len(leaks) >= 10:
                break
    if leaks:
        raise ValueError(f"{target}: Stonecutter directives leaked into standalone source: {', '.join(leaks)}")

    forbidden = (
        "source-shared",
        "source-families",
        "source-platforms",
        "source-family-platforms",
        "source-downports",
        "version-src",
        "build-config",
        "build-logic",
        "builds",
    )
    present = [name for name in forbidden if (project_root / name).exists()]
    if present:
        raise ValueError(f"{target}: repository-only directories leaked into standalone export: {', '.join(present)}")


def export_standalone_project(
    target: str,
    destination: Path | None = None,
    properties: dict[str, str] | None = None,
) -> Path:
    props = properties or load_properties()
    if target not in target_ids(props):
        raise ValueError(f"Unknown BuildCraft target: {target}")

    output = (destination or (ROOT / "build/materialized-projects" / target)).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix=f"bc-materialize-{target}-", dir=output.parent) as temporary:
        temporary_root = Path(temporary)
        effective = materialize_target(target, temporary_root / "effective", props)
        project = temporary_root / "project"
        project.mkdir(parents=True)
        shutil.copytree(effective / "src", project / "src")

        _apply_compile_exclusions(project, props, target)
        _write_buildcraft_target(project, props, target)
        _expand_target_resources(project, props, target)
        _write_gradle_project(project, props, target)
        _write_root_files(project, props, target)
        _verify_project(project, target)

        if output.exists():
            shutil.rmtree(output)
        shutil.move(str(project), str(output))

    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", nargs="?", help="target id, e.g. 1.20.1-forge")
    parser.add_argument("--output", type=Path, help="standalone project destination")
    parser.add_argument("--list-targets", action="store_true", help="print configured target ids")
    args = parser.parse_args()

    properties = load_properties()
    configured = target_ids(properties)
    if args.list_targets:
        print("\n".join(configured))
        return 0
    if not args.target:
        parser.error("target or --list-targets is required")
    if args.target not in configured:
        parser.error(f"target {args.target!r} is not configured; choose one of: {', '.join(configured)}")

    output = export_standalone_project(args.target, args.output, properties)
    print(f"Materialized {args.target}")
    print(f"Created: {output}")
    print("Build with:")
    print(f"  cd {output}")
    print("  ./gradlew build")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
