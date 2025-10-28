// file: app/build.gradle.kts
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // ---- Load local.properties once & tiny helpers ----
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun prop(name: String, default: String = ""): String =
        (project.findProperty(name) as String?)
            ?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty(name)
                ?.takeIf { it.isNotBlank() }
            ?: default
    fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ===== Single source of truth for appId (override via local.properties: appId=...) =====
    val appId = prop("appId", "com.negi.survey")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Use AndroidX Test Runner (required for Orchestrator)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Strongly recommended when using Orchestrator on Android 14+:
        // - clearPackageData: isolates app state between test invocations
        // - useTestStorageService: uses scoped test storage instead of legacy external storage
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    // Always run androidTest against the debug build (same applicationId)
    testBuildType = "debug"

    testOptions {
        // ✅ Enable Android Test Orchestrator (each test runs in its own Instrumentation instance)
        //    This drastically reduces flakiness caused by shared state between tests.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        // Optional but helps reduce UI flakiness during Espresso/UI tests
        animationsDisabled = true

        // If you need resources in local unit tests, uncomment:
        // unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug {
            // NOTE: Do not use applicationIdSuffix; keep a stable package for MediaStore ownership.
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
            // Use debug signing for convenience in CI/dev
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Broad META-INF excludes to avoid conflicts among OkHttp/Coroutines/Media3/MediaPipe, etc.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // ===== Compose BOM =====
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation3.runtime)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Kotlin / Coroutines / Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Core / AppCompat
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Jetpack Navigation 3
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    // Kotlin and Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // Compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Debug/Preview
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Activity / Navigation / Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // MediaPipe GenAI
    implementation(libs.mediapipe.tasks.genai)

    // SAF (androidTest uses DocumentFile)
    androidTestImplementation(libs.androidx.documentfile)

    // ===== Test libs =====
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    // AndroidX Test
    androidTestImplementation(libs.androidx.junit)            // androidx.test.ext:junit
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockito.android)

    // ✅ Orchestrator runner dependency (explicit)
    // Some setups get it transitively, but declare explicitly to avoid surprises.
    // If you have a version catalog alias, prefer that; otherwise use a literal coordinate.
    androidTestImplementation(libs.androidx.test.runner)
    // androidTestImplementation("androidx.test:runner:1.5.2") // ← fallback if no alias

    // ✅ Orchestrator itself (required when execution = ANDROIDX_TEST_ORCHESTRATOR)
    androidTestUtil(libs.androidx.test.orchestrator) // e.g., 1.5.1
}
