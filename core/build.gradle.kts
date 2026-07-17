plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    // 仅 JVM 真实阅读测试需要 ImageIO WebP 插件；Android 使用 Coil/Bitmap 解码。
    testRuntimeOnly(libs.webp.imageio)
}
