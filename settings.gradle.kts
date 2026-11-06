pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
        google {
            content {
                excludeGroupByRegex("top\\.yukonga\\.miuix\\.kmp.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Raidian"
include(":app")
include(":libadb")
project(":libadb").projectDir = file("third_party/libadb")
