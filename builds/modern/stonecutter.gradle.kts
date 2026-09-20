import java.util.Properties

plugins {
    id("dev.kikugie.stonecutter")
}

val repositoryRoot = rootProject.file("../..").canonicalFile
val targetRegistry = Properties().apply {
    repositoryRoot.resolve("build-config/targets.properties").inputStream().use { load(it) }
}
val generationConfiguration = Properties().apply {
    rootProject.file("targets.properties").inputStream().use { load(it) }
}
val generation = generationConfiguration.getProperty("generation")?.trim()?.takeIf(String::isNotEmpty)
    ?: error("Missing generation in targets.properties")
val targets = targetRegistry.getProperty("targets")
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    ?.filter { target -> targetRegistry.getProperty("target.$target.build.generation")?.trim() == generation }
    ?: error("Missing non-empty targets in build-config/targets.properties")

stonecutter active "1.21.11-neoforge" /* [SC] DO NOT EDIT */

stonecutter {
    parameters {
        val target = node.metadata.project
        val loader = targetRegistry.getProperty("target.$target.source.platform")?.trim()
            ?: error("Missing source.platform for $target")
        constants += listOf(
            "forge" to (loader == "forge"),
            "neoforge" to (loader == "neoforge"),
            "fabric" to (loader == "fabric"),
            "legacy" to (generation == "legacy"),
            "modern" to (generation == "modern"),
        )
    }
}

tasks.register("buildAndCollect") {
    group = "build"
    description = "Build every $generation BuildCraft target and collect release jars"
    dependsOn(targets.map { target -> ":$target:buildAndCollect" })
}

val activeProject = stonecutter.current!!.project
fun activeTask(name: String) = ":$activeProject:$name"

tasks.register("runActiveClient") {
    group = "stonecutter"
    dependsOn(activeTask("runClient"))
}

tasks.register("runActiveServer") {
    group = "stonecutter"
    dependsOn(activeTask("runServer"))
}

tasks.register("runActiveGameTests") {
    group = "verification"
    dependsOn(activeTask("runGameTestServer"))
}
