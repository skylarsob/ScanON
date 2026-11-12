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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Local repo: the two artifacts missing from this machine's Gradle
        // cache (lifecycle-viewmodel-compose, tracing 1.2.0), fetched from
        // Google Maven. Everything else resolves from google()/cache.
        maven { url = uri(rootDir.resolve("local-maven")) }
        google()
        mavenCentral()
    }
}
rootProject.name = "ScanON"
include(":app")
