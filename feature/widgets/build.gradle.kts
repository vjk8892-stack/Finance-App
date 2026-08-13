plugins {
    id("kosha.android.feature")
}

android {
    namespace = "dev.kosha.feature.widgets"
}

dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
