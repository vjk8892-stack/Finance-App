plugins {
    id("kosha.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.kosha.core.database"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
