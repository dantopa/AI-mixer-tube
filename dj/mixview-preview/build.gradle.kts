plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xmulti-dollar-interpolation") }
    sourceSets {
        main {
            // The builder (data model lives in :brain) and the one engine class it projects with.
            kotlin.srcDir("../android/src/main/kotlin/org/simpmusic/dj/android/mixview")
            kotlin.srcDir("../android/src/main/kotlin/org/simpmusic/dj/android/window")
            // The composables, minus the resource-backed wrappers (they need composeApp's generated Res).
            kotlin.srcDir("../../composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/dj")
            // DjMixCard.kt / DjMixSheet.kt need composeApp's generated Res, icons and surface colours: compile-only stubs.
            kotlin.srcDir("src/stubs/kotlin")
            // Only WindowTimeline from the window package (the rest is Media3/Android).
            kotlin.exclude { it.file.invariantSeparatorsPath.contains("/android/window/") && it.name != "WindowTimeline.kt" }
        }
        test {
            kotlin.srcDir("../android/src/test/kotlin/org/simpmusic/dj/android/mixview")
        }
    }
}

dependencies {
    implementation("org.simpmusic.dj:brain")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.components:components-resources:1.12.1")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures("org.simpmusic.dj:brain"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Djava.awt.headless=true")
    testLogging { events("failed", "skipped"); showStandardStreams = true }
    // The rendering test reads these; unset = skipped.
    environment("MIXVIEW_OUT", System.getenv("MIXVIEW_OUT") ?: "")
    environment("DJ_CACHE", System.getenv("DJ_CACHE") ?: "")
}
