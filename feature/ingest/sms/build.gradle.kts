plugins {
    id("kosha.android.feature")
}

android {
    namespace = "dev.kosha.feature.ingest.sms"
}

dependencies {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
