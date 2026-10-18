pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DawnYew"
include(":shared:core", ":shared:storage", ":shared:protocol", ":shared:presentation", ":shared:ui")
include(":platform:android", ":androidApp")
