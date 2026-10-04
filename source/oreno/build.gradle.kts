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
    implementation(project(":core:foundation"))
    implementation(project(":core:network"))
    implementation(libs.jsoup)
    // Jsoup publishes its nullness annotations as optional; Kotlin needs them when inferring Java types.
    compileOnly(libs.jspecify)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
