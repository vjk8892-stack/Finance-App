plugins {
    id("kosha.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.kosha.feature.export"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    androidTestImplementation(libs.androidx.test.junit)
}
