plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":domain:video"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
