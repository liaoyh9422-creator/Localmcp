pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Chaquopy plugin marker also published to Maven Central
        maven { url = uri("https://chaquo.com/maven") }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.chaquo.python") {
                useModule("com.chaquo.python:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven") }
        // R8 for on-device dexing
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
    }
}

rootProject.name = "ApksToApk"
include(":app")
