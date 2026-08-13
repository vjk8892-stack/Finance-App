// Standalone pure-JVM build: the Kosha engines (spec Part E — parser, dedup,
// forecast, debt, analytics engines are pure Kotlin with exhaustive unit
// fixtures). This build has ZERO Android/AGP dependency, so its tests run in
// any environment with a JDK: `gradle test` from this directory, or
// `./gradlew -p engines test` from the repo root.
//
// The Android build consumes these modules via composite-build substitution
// (`includeBuild("engines")` in the root settings) as dev.kosha:common and
// dev.kosha:engine.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "kosha-engines"

include(":common")
include(":engine")
