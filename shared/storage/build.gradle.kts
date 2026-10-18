plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvmToolchain(17)
    android {
        namespace = "com.dawnyew.storage"
        compileSdk = 37
        minSdk = 30
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(project(":shared:protocol"))
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

room { schemaDirectory("$projectDir/schemas") }
