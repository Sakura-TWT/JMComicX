plugins {
    id("com.android.library")
}

android {
    namespace = "app.prismia.database.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
