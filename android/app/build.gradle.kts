plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PappVersionName=1.2.3 -PappVersionCode=42 for tagged releases.
val appVersionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "io.craigcloud.disneyjeopardy"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "io.craigcloud.disneyjeopardy"
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "DEFAULT_URL", "\"https://jeopardy.craigcloud.io/tv\"")
    }
    // Sideload-only key, committed on purpose so every build (local or CI) can update an installed copy.
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "disneyjeopardy"
            keyAlias = "disneyjeopardy"
            keyPassword = "disneyjeopardy"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sideload") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
