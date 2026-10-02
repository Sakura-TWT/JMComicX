plugins {
    id("com.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.prismia.designsystem"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.blur.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.nav.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
}
