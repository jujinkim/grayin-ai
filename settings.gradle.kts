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
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPackTesseract4Android"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeModule("cz.adaptech.tesseract4android", "tesseract4android")
            }
        }
    }
}

rootProject.name = "GrayinAI"
include(":app")
