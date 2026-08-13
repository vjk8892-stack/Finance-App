plugins {
    id("kosha.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        compose = true
    }
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    val bom = libs.findLibrary("compose-bom").get()
    "implementation"(platform(bom))
    "androidTestImplementation"(platform(bom))
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-ui-graphics").get())
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
}
