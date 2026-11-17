plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lumen.desk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lumen.desk"
        // Android 7.0 (API 24) and newer, so old tablets work too.
        minSdk = 24
        targetSdk = 34
        // Grows with every build (minutes since 2026), so the PC can tell which tablet app is newer.
        versionCode = ((System.currentTimeMillis() / 60_000L) - 29_454_000L).toInt().coerceAtLeast(2)
        versionName = "1.2"
    }

    sourceSets {
        getByName("main") {
            // Icons are shared by the tablet and the PC (shared/icons.json).
            assets.srcDirs("src/main/assets", "../../shared")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
