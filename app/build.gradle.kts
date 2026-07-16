plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

@Suppress("UnstableApiUsage")
android {
    namespace = "dev.jmx.client"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "dev.jmx.client"
        minSdk = 33
        targetSdk = 37
        versionCode = 13
        versionName = "0.13.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.gson)
    implementation(libs.miuix.blur.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
    implementation(libs.okhttp)
    implementation(libs.opencc4j)

    testImplementation(libs.junit)
}
