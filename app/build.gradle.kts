// file: app/build.gradle.kts
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.negi.surveynav"
    compileSdk = 36

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

    defaultConfig {
        applicationId = "com.negi.surveynav"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ---- GitHub BuildConfig (always available) ----
        buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
        buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
        buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
        buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            // debug でも release でも -Pgh.token を取り込む（未設定なら空）
            buildConfigField("String", "GH_TOKEN", quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN", quote(prop("HF_TOKEN")))
        }
        release {
            isMinifyEnabled = false // 必要なら true にして難読化
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "GH_TOKEN", quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN", quote(prop("HF_TOKEN")))

            // デモ用：debug 証明書で署名（必要なら本番署名に切替）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    // ===== Compose BOM（Version Catalog の BOM を使う）=====
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    // ===== Kotlin / Coroutines / Serialization =====
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ===== Core / AppCompat =====
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // ===== Compose UI 基本 =====
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

    // ===== Activity / Navigation / Lifecycle =====
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3.android)

    // ===== WorkManager =====
    implementation(libs.androidx.work.runtime.ktx)

    // ===== Networking =====
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // ===== Media3（必要なら）=====
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // ===== MediaPipe GenAI（必要な方のみ）=====
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)

    // ===== Accompanist（必要なら）=====
    implementation(libs.accompanist.navigation.animation)

    // ===== Test =====
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // ---- （削除したものの例）----
    // implementation(libs.firebase.crashlytics.buildtools) // ← アプリ依存ではなく、Gradleプラグインで使うもの
    // implementation(platform("androidx.compose:compose-bom:2025.09.00")) // ← 固定版を手書きしない
    // 重複していた serialization/json, okhttp, ui.tooling.preview, activity.compose などを整理
}
