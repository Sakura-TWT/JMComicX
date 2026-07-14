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
    // ImageIO WebP 插件（禁漫 CDN 图片常用 webp；JDK 内置 ImageIO 无 webp）
    implementation(libs.webp.imageio)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
