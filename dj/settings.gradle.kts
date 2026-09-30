// Standalone build for the DJ "brain" (pure Kotlin/JVM). Kept apart from the app build so it can be
// compiled and tested in seconds without Android, KSP or the core submodule.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "dj"
include(":brain")
