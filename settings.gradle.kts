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
        maven {
            url = uri("https://android-sdk.is.com/")
            content { includeGroup("com.ironsource.sdk") }
        }
        maven {
            url = uri("https://artifacts.applovin.com/android")
            content { includeGroup("com.applovin") }
        }
        maven {
            url = uri("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
            content { includeGroupByRegex("com\\.mbridge.*") }
        }
        maven {
            url = uri("https://artifact.bytedance.com/repository/pangle/")
            content { includeGroupByRegex("com\\.pangle.*") }
        }
    }
}

rootProject.name = "android-native-camera"
include(":app")
