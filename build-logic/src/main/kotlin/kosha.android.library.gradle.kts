import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-junit").get())
    // The runner named in testInstrumentationRunner above lives in
    // androidx.test:runner, and androidx.test.ext:junit does not bring it.
    // :core:database only had it by accident, through espresso; :feature:export
    // did not, so its instrumentation process died on start and the task
    // reported "Starting 0 tests" — a green-looking run of nothing. Declared
    // here, next to the runner it satisfies, so no module can lose it again.
    "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
}
