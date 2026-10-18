plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
    android {
        namespace = "com.dawnyew.ui"
        compileSdk = 37
        minSdk = 30
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:presentation"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(libs.junit) }
    }
}
