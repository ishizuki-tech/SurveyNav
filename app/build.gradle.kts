import java.util.Properties

// file: app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.negi.surveynav"
    compileSdk = 36

    // ---- load props once & helpers (usable in defaultConfig/buildTypes) ----
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

        // ---- GitHub BuildConfig (available in all buildTypes) ----
        buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
        buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
        buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
        buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
    }

    buildFeatures {
        buildConfig = true     // enable BuildConfig generation
        compose = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "GH_TOKEN", quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN", quote(prop("HF_TOKEN")))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            buildConfigField("String", "GH_TOKEN", quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN", quote(prop("HF_TOKEN")))

            // demo: sign with debug keystore (replace as needed)
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

    // Compose BOM（バージョンはそのままでもOK。将来あげる時は一括で）
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.okhttp)
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)

    implementation(libs.androidx.compose.material3.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.benchmark.traceprocessor.android)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)

    // AppCompat 系（これが無いと AppCompatActivity / AppCompatDelegate が解決しない）
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    // Compose 基本
    implementation(libs.ui)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.foundation)
    implementation(libs.material3)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.runtime.saveable)

    // Icons（BOMに任せる！）
    implementation(libs.androidx.material.icons.extended)

    // Activity / Navigation / Lifecycle（重複＆古い方を削除）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // ---- OkHttp / JSON ----
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.dnsoverhttps)

    // ---- MediaPipe GenAI ----
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)

    // ---- Accompanist（必要なら）----
    implementation(libs.accompanist.navigation.animation)

    // ---- Debug / Test ----
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
