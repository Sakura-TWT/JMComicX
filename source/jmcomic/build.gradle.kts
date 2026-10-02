plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":core"))
    api(project(":domain:comic"))
    implementation(project(":core:foundation"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
