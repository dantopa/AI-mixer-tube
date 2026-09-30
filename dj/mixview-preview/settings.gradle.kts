// Standalone build: renders the AI DJ mix view (composeApp/.../ui/component/dj) off-screen to PNG on the JVM and runs
// the JVM tests of the mixview data builder, without Android, KSP or the core submodule. The sources are compiled from
// where they live (no copy). See dj/docs/mixview.md.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "mixview-preview"
includeBuild("..")
