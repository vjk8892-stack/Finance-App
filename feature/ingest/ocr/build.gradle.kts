plugins {
    id("kosha.android.feature")
}

android {
    namespace = "dev.kosha.feature.ingest.ocr"
}

dependencies {
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // On-device only. No cloud fallback — by design (spec B1).
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.activity.compose)
}
