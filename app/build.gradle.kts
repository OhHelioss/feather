plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.feather.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.feather.browser"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Sideload key committed to the repo so every build can update the previous one.
        // For Google Play, sign with your own upload key instead.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "feather-sideload"
            keyAlias = "feather"
            keyPassword = "feather-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

dependencies {
    // Only for per-tab profiles (private tabs) and document-start scripts; R8 strips what is unused.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
