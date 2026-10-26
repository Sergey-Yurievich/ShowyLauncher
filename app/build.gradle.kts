import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The TMDB key stays out of the sources: local.properties (not committed) holds `tmdb.apiKey=...`.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tmdbKey: String = localProps.getProperty("tmdb.apiKey", "")

android {
    namespace = "ru.homescreen.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.homescreen.tv"
        minSdk = 28
        // Google TV 14 refuses sideloads with an old target; 34 also keeps Android 12 behaviour predictable.
        targetSdk = 34
        versionCode = 11
        versionName = "1.9"
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded over ADB, never through a store. Releases are signed with the maintainer's debug keystore:
            // updates install over an existing launcher only with that same key, so it is kept backed up.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.hls)
    implementation(libs.media3.okhttp)
    testImplementation(libs.junit4)
    // The real org.json: Android's is a stub in unit tests.
    testImplementation(libs.json)
}
