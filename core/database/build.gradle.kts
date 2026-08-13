plugins {
    id("kosha.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.kosha.core.database"
}

// The Room Gradle plugin registers the schema directory as a real task
// output. Setting `room.schemaLocation` as a bare KSP arg instead leaves the
// directory untracked, which lets the Gradle build cache restore a partial
// schema JSON and fail the KSP task (spec B5 requires exported schemas for
// the migration tests, so turning export off is not an option).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // api: Money and the period engine types appear in repository signatures.
    api("dev.kosha:common")
    api("dev.kosha:engine")

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.datastore.preferences)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.espresso)
}
