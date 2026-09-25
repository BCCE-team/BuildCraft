import java.util.Properties

pluginManagement {
    // Resolve loader-plugin versions from the canonical target registry so the
    // Stonecutter build root cannot drift away from materialized projects.
    val repositoryRoot = file("../..").canonicalFile
    val targetRegistry = Properties().apply {
        repositoryRoot.resolve("build-config/targets.properties").inputStream().use { load(it) }
    }
    val generationConfiguration = Properties().apply {
        file("targets.properties").inputStream().use { load(it) }
    }
    val generation = generationConfiguration.getProperty("generation")?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing generation in targets.properties")
    val fabricLoomVersions = targetRegistry.getProperty("targets")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter { targetRegistry.getProperty("target.$it.build.generation")?.trim() == generation }
        .filter { targetRegistry.getProperty("target.$it.source.platform")?.trim() == "fabric" }
        .map { targetId ->
            targetRegistry.getProperty("target.$targetId.deps.fabric_loom")?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing target.$targetId.deps.fabric_loom in canonical target registry")
        }
        .distinct()
    if (fabricLoomVersions.size > 1) {
        error("Fabric targets in generation '$generation' require different Loom versions: $fabricLoomVersions")
    }
    val fabricLoomVersion = fabricLoomVersions.singleOrNull()

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "fabric-loom") {
                useVersion(fabricLoomVersion ?: error("No Fabric Loom version configured for generation '$generation'"))
            }
        }
    }

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.7.11"
}

val repositoryRoot = file("../..").canonicalFile
val commonConfiguration = Properties().apply {
    repositoryRoot.resolve("build-config/common.properties").inputStream().use { load(it) }
}
val targetRegistry = Properties().apply {
    repositoryRoot.resolve("build-config/targets.properties").inputStream().use { load(it) }
}
val generationConfiguration = Properties().apply {
    file("targets.properties").inputStream().use { load(it) }
}
val generation = generationConfiguration.getProperty("generation")?.trim()?.takeIf(String::isNotEmpty)
    ?: error("Missing generation in targets.properties")

fun property(key: String): String =
    generationConfiguration.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: targetRegistry.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: commonConfiguration.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing required property '$key' in canonical BuildCraft configuration")

val targets = property("targets").split(',').map(String::trim).filter(String::isNotEmpty).filter { targetId ->
    property("target.$targetId.build.generation") == generation
}
if (targets.isEmpty()) error("No targets configured for build generation '$generation'")

stonecutter {
    kotlinController = true
    create(rootProject) {
        for (targetId in targets) {
            val loader = property("target.$targetId.source.platform")
            version(targetId, property("target.$targetId.deps.minecraft"))
                .buildscript("build.$loader.gradle")
        }
        vcsVersion = property("vcsTarget")
    }
}

rootProject.name = "BuildCraft-Legacy"
