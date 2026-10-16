plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional: bake your Telegram API credentials into the build (e.g. from GitHub secrets).
// If they are left empty the app asks for them on first launch instead.
val tgApiId: String = (findProperty("TG_API_ID") as String?) ?: System.getenv("TG_API_ID") ?: "0"
val tgApiHash: String = (findProperty("TG_API_HASH") as String?) ?: System.getenv("TG_API_HASH") ?: ""

android {
    namespace = "com.savedcloud.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.savedcloud.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        // The bundled TDLib native library is arm64 only (all modern phones).
        ndk { abiFilters += listOf("arm64-v8a") }

        buildConfigField("int", "TG_API_ID", if (tgApiId.isBlank()) "0" else tgApiId.trim())
        buildConfigField("String", "TG_API_HASH", "\"${tgApiHash.trim()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Extract libtdjni.so to disk at install time (same as the original Unlim app) instead of
    // mapping it straight out of the APK, which crashes on some devices.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
