// file: app/build.gradle.kts

import java.util.Properties

plugins {
    // Use Gradle version catalog aliases for plugins
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Load secrets or environment-specific values from local.properties
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    /**
     * Utility function to resolve a property.
     * First checks Gradle -P command line properties,
     * then local.properties, then returns a default.
     * This avoids hardcoding secrets in source control.
     */
    fun prop(name: String, default: String = "") =
        (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: default

    /**
     * Escapes a string value so that it can be safely used in BuildConfig fields.
     */
    fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Define the application ID from local.properties or use default
    val appId = prop("appId", "com.negi.survey")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Use AndroidX test runner for instrumentation
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Recommended test instrumentation arguments for stability and isolation
        testInstrumentationRunnerArguments["clearPackageData"] = "true" // Isolates app data between tests
        testInstrumentationRunnerArguments["useTestStorageService"] = "true" // Use scoped storage
        testInstrumentationRunnerArguments["numShards"] = "1" // Avoid unintentional test sharding
    }

    // Set test build variant to debug for instrumentation testing
    testBuildType = "debug"

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR" // Enables Orchestrator for isolated test execution
        animationsDisabled = true // Reduces flakiness during UI tests
    }

    buildFeatures {
        buildConfig = true // Enable BuildConfig generation
        compose = true // Enable Jetpack Compose features
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17" // Keep Kotlin/JVM target consistent with Java version
    }

    buildTypes {
        debug {
            // Inject GitHub and HuggingFace secrets via BuildConfig
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }
        release {
            isMinifyEnabled = false // Proguard minification disabled for now
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Reuse debug signing config for testing/internal builds
            signingConfig = signingConfigs.getByName("debug")

            // Same secrets used in release build
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }
    }

    // Avoid packaging conflicts with third-party libraries
    packaging.resources.excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/INDEX.LIST",
        "META-INF/AL2.0",
        "META-INF/LGPL2.1",
        "/META-INF/LICENSE.md",
        "/META-INF/LICENSE-notice.md",
        "META-INF/*.kotlin_module"
    )
}

dependencies {
    // Compose BOM ensures consistent Compose versioning
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Core UI + Layout
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.compose.foundation.layout)

    // Jetpack Navigation 3
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    // Kotlin and Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // Android Core + AppCompat
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Core + UI + Material
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Debug/Preview Only
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Lifecycle + Navigation + Activity
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3.android)

    // Background Work (WorkManager)
    implementation(libs.androidx.work.runtime.ktx)

    // Network (OkHttp + DoH)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Media (Media3 + ExoPlayer UI)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // AI (MediaPipe Tasks for GenAI)
    implementation(libs.mediapipe.tasks.genai)

    // UI Animations (Accompanist)
    implementation(libs.accompanist.navigation.animation)

    // Storage (DocumentFile)
    androidTestImplementation(libs.androidx.documentfile)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    // Instrumentation Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.androidx.test.runner)

    // Android Test Orchestrator support
    androidTestUtil(libs.androidx.test.orchestrator)
}

// Task: Print resolved instrumentation test arguments
tasks.register("printAndroidTestArgs") {
    group = "verification"
    description = "Print resolved default instrumentation runner arguments."
    doLast {
        println("=== Default Instrumentation Args ===")
        val args = android.defaultConfig.testInstrumentationRunnerArguments
        args.forEach { (k, v) -> println(" - $k = $v") }
        println("===================================")
        println("Tip: CI can override via -Pandroid.testInstrumentationRunnerArguments.<key>=<value>")
    }
}

// Task: CI safety check to ensure only one device is connected
tasks.register("checkSingleConnectedDevice") {
    group = "verification"
    description = "Fails if more than one device is connected (helps avoid double runs)."
    doLast {
        val process = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val lines = out.lineSequence().drop(1).map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("\tdevice") }.toList()
        println("Connected devices: ${lines.size}")
        lines.forEach { println(" - $it") }
        if (lines.size > 1) {
            throw GradleException(
                "More than one device/emulator is connected. " +
                        "Run `adb devices -l` and keep exactly one to avoid duplicate test runs."
            )
        }
    }
}
