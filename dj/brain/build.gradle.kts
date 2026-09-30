plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `java-test-fixtures`
    `maven-publish`
}

group = "org.simpmusic.dj"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testFixturesApi(kotlin("test"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}
