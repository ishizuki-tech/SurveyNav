// file: app/build.gradle.kts
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // ---- load props once & helpers ----
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

    // ===== アプリIDを一元化（local.properties で appId を上書き可）=====
    val appId = prop("appId", "com.negi.survey")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ★ Android 14+ 端末での androidTest 実行安定化
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true" // ★ 追加
    }

    // androidTest は常に debug 対象で実行（applicationId を揃える）
    testBuildType = "debug"

    // ★ Orchestrator 有効化（依存も必ず追加すること！）
    testOptions {
        //execution = "ANDROIDX_TEST_ORCHESTRATOR"
        execution= "HOST"
        animationsDisabled = true
        // unitTests.isIncludeAndroidResources = true // ←(必要なら)
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
            // ★ applicationIdSuffix は付けない（MediaStore の OWNER_PACKAGE_NAME 再利用のため）
            // applicationIdSuffix = ""

            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }
        release {
            // 同一 appId を維持（suffix 禁止）
            // applicationIdSuffix = ""
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // META-INF の重複を幅広く除外（OkHttp/Coroutines/Media3/MediaPipe 混在時の衝突回避）
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
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    // Kotlin / Coroutines / Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Core / AppCompat
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.runtime.saveable)

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

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // MediaPipe GenAI
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)

    // Accompanist
    implementation(libs.accompanist.navigation.animation)

    // SAF（androidTest で DocumentFile を使う）
    androidTestImplementation(libs.androidx.documentfile)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)           // androidx.test.ext:junit
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockito.android)

    androidTestUtil(libs.androidx.test.orchestrator) // 例: 1.5.1
}
