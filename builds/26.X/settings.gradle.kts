pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}

// Stonecutter normally sets the project name to the selected target.  This
// standalone 26.X build keeps the same invariant so common-target.gradle can
// resolve the canonical target metadata.
rootProject.name = "26.1.2-neoforge"
rootProject.buildFileName = "build.neoforge.gradle"
