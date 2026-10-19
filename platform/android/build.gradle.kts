plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dawnyew.platform"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("crypto-consumer-rules.pro", "ocr-consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/opencv-4.14.0.aar"))
    implementation(files("libs/onnxruntime-no-telemetry-1.31.0.aar"))
    api(project(":shared:core"))
    implementation(project(":shared:protocol"))
    implementation(project(":shared:storage"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.jackson.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", rootProject.layout.projectDirectory.dir(".local/crypto-work/host").asFile.absolutePath)
}


// Run this gate for each deliverable Release APK; it inspects DEX definitions,
// not just rules or mapping text, and rejects a non-obfuscated app build.
tasks.register<Exec>("verifyReleaseOcrJni") {
    group = "verification"
    description = "Build minified Release and verify the complete OCR JNI binding ABI in its DEX"
    dependsOn(":androidApp:assembleRelease")
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build-ocr-verify-release.py", "--report",
        "build/reports/ocr-release-jni-verification.json")
}
