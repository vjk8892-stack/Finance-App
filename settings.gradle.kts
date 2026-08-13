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
    }
}

rootProject.name = "kosha"

// Pure-JVM engines (spec Part E) live in a standalone composite build so
// they build/test with a bare JDK — no Android SDK, no Google Maven.
// Android modules depend on them as dev.kosha:common / dev.kosha:engine.
includeBuild("engines")

include(":app")
include(":core:designsystem")
include(":core:database")
include(":feature:ledger")
include(":feature:ingest:sms")
include(":feature:ingest:ocr")
include(":feature:ingest:review")
include(":feature:budget")
include(":feature:income")
include(":feature:insights")
include(":feature:goals")
include(":feature:vault")
include(":feature:export")
include(":feature:widgets")
