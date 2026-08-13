plugins {
    id("kosha.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.kosha.feature.ledger"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
