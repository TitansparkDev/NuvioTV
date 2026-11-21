pluginManagement {
    val agpVersion = file("gradle/libs.versions.toml").readLines()
        .first { it.trimStart().startsWith("agp =") }
        .substringAfter('"').substringBefore('"')
    plugins {
        id("com.android.settings") version agpVersion
    }
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
    id("com.android.settings")
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
android {
    execution {
        profiles {
            create("bounded") {
                r8 {
                    runInSeparateProcess = true
                    jvmOptions += listOf("-Xms256m", "-Xmx6144m")
                }
            }
            create("in-process") {
                r8 {
                    runInSeparateProcess = false
                }
            }
            defaultProfile = "bounded"
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "My Application"
include(":app")
include(":baselineprofile")
include(":ffmpeg-decoder-downmix")
