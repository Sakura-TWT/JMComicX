plugins {
    id("com.android.library")
}

android {
    namespace = "app.prismia.feature.shell"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

}

dependencies {
    implementation(project(":core:foundation"))
    implementation(project(":core:designsystem"))
    implementation(libs.kotlinx.coroutines.core)
}
