plugins {
    id("kosha.android.library.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"("dev.kosha:common")
    "implementation"("dev.kosha:engine")
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:database"))

    "implementation"(libs.findLibrary("compose-material-icons").get())
    "implementation"(libs.findLibrary("hilt-android").get())
    "ksp"(libs.findLibrary("hilt-compiler").get())
    "implementation"(libs.findLibrary("hilt-navigation-compose").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    "implementation"(libs.findLibrary("androidx-navigation-compose").get())
    "implementation"(libs.findLibrary("kotlinx-coroutines-android").get())
}
