@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeModule("cz.adaptech.tesseract4android", "tesseract4android")
            }
        }
    }
}

rootProject.name = "athar"

include(":app")

include(":core:common")
include(":core:domain")
include(":core:data")
include(":core:design-system")
include(":core:testing")

include(":feature:today")
include(":feature:trends")
include(":feature:plan")
include(":feature:settings")
include(":feature:widgets")

include(":ingestion:sms-parser")
include(":ingestion:sms-listener")
include(":ingestion:notification-listener")

include(":ml:categorizer")
include(":ml:models")
