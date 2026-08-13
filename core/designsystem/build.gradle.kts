plugins {
    id("kosha.android.library.compose")
}

android {
    namespace = "dev.kosha.core.designsystem"
}

dependencies {
    implementation("dev.kosha:common")
    implementation(libs.compose.material.icons)
}
