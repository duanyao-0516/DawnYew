plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.dawnyew.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    ndkPath = rootProject.file(".local/android-ndk-r28c").absolutePath
    defaultConfig {
        applicationId = "com.dawnyew.app"
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += providers.gradleProperty("dawnyewAbis").orElse("arm64-v8a").get().split(",") }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".local/signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures { compose = true }
    buildTypes {
        debug {
            // Device validation can coexist with a release installation and its private dataset.
            if (providers.gradleProperty("dawnyewIsolatedDeviceTest").orNull == "true") applicationIdSuffix = ".validation"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:storage"))
    implementation(project(":shared:protocol"))
    implementation(project(":shared:presentation"))
    implementation(project(":shared:ui"))
    implementation(project(":platform:android"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
