plugins {
    kotlin("jvm") version "2.4.20"
}

group = "org.simpmusic.dj"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":brain"))
    // JVM artifact for the tests / desktop. Android swaps it for `onnxruntime-android` (same `ai.onnxruntime`
    // package), so only OnnxBeatModel touches it and everything else is pure Kotlin.
    api("com.microsoft.onnxruntime:onnxruntime:1.22.0")

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":brain")))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging { events("failed", "skipped"); showStandardStreams = false }
    // Optional real-model tests: point at the ONNX file and the corpus, they skip themselves otherwise.
    listOf("BEAT_THIS_MODEL", "BEAT_THIS_CORPUS").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
