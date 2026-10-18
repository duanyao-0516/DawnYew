plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    android {
        namespace = "com.dawnyew.protocol"
        compileSdk = 37
        minSdk = 30
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(libs.junit) }
    }
}
