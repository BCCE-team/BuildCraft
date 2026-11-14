import java.util.Properties

pluginManagement {
    repositories {
        // Resolve Fabric's own Gradle implementation from its publishing
        // repository before general plugin repositories. A repository that
        // serves partial/stale module metadata may prevent later fallbacks.
        maven("https://maven.fabricmc.net/") {
            name = "FabricMC"
            content { includeGroupByRegex("net\\.fabricmc(\\..*)?") }
        }
        // Independent Fabric CDN and mirror are fallbacks for transient or
        // regional failures. Neither changes the pinned Loom version.
        maven("https://fabricmc.forgecdn.net/") {
            name = "FabricMC CDN"
            content { includeGroupByRegex("net\\.fabricmc(\\..*)?") }
        }
        maven("https://maven.modmuss50.me/") {
            name = "FabricMC Mirror"
            content { includeGroupByRegex("net\\.fabricmc(\\..*)?") }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.fabricmc.fabric-loom") {
                // The official Maven publishes net.fabricmc:fabric-loom alongside a
                // marker artifact. Resolve the implementation directly to avoid
                // marker lookup failures in the Stonecutter buildscript shim.
                val version = requested.version
                    ?: error("Fabric Loom plugin requires an explicit version")
                useModule("net.fabricmc:fabric-loom:$version")
            }
        }
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

rootProject.name = "BuildCraft-26.X"
