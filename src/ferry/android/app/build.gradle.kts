plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.onyxbox.ferry"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.onyxbox.ferry"
        // Target 28 on purpose: lets Ferry switch Wi-Fi/Bluetooth on by itself (blocked for newer targets).
        minSdk = 28
        targetSdk = 28
        versionCode = 2
        versionName = "2.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file("../ferry.jks")
            storePassword = "ferryferry"
            keyAlias = "ferry"
            keyPassword = "ferryferry"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
