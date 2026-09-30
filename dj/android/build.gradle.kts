// :djAndroid - the Android half of the AI DJ: audio decoding, analysis scheduling/storage, settings,
// and the transition-window engine that the (patched) CrossfadeExoPlayerAdapter drives.
// The musical "brain" (analysis contract, planner, renderer) is the pure-JVM `dj` composite build.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        freeCompilerArgs.add("-Xwhen-guards")
    }
}

android {
    namespace = "org.simpmusic.dj.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The brain: model contract + planner + renderer (composite build, substituted by group:name).
    api("org.simpmusic.dj:brain")
    // Beat This! neural beat tracker: the ml module is pure Kotlin except for the ONNX runtime, which it declares as the
    // desktop JVM artifact. Android must use the AAR (same ai.onnxruntime package), so the JVM one is excluded.
    implementation("org.simpmusic.dj:ml") { exclude(group = "com.microsoft.onnxruntime") }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    implementation(libs.core.ktx)
    implementation(projects.common)
    implementation(projects.domain)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // Media3: cache-backed DataSource for decoding, ExoPlayer for the window/deck implementations.
    api(libs.media3.exoplayer)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
    testImplementation(testFixtures("org.simpmusic.dj:brain"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
