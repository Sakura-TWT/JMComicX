plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":core:foundation"))
    api(project(":domain:video"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
