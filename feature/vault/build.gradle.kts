plugins {
    id("kosha.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.kosha.feature.vault"
}

dependencies {
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
}
