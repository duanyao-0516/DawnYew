plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)
    android {
        namespace = "com.dawnyew.presentation"
        compileSdk = 37
        minSdk = 30
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            implementation(project(":shared:protocol"))
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies { implementation(libs.junit) }
    }
}
