import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val visualMockitoAgent = configurations.create("visualMockitoAgent").apply { isTransitive = false }

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseKeystoreFile = providers.environmentVariable("JMX_RELEASE_KEYSTORE_FILE")
    .orElse("signing/jmx-release.keystore")
    .get()
    .let(::file)
val releaseStorePassword = providers.environmentVariable("JMX_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("JMX_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("JMX_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = releaseKeystoreFile.isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

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
        versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("jmxRelease") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("jmxRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 设置页里的"漫画更新提示"自测入口（立即检查 / 模拟一次 / 清除全部）只在调试构建出现；
            // release 为编译期常量 false，整块被折叠掉，正式包不含这几个按钮。功能本身照常发布。
            buildConfigField("boolean", "COMIC_UPDATE_HINTS", "false")
        }
        getByName("debug") {
            buildConfigField("boolean", "COMIC_UPDATE_HINTS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("robolectric.useRealDrawTraversals", "true")
            it.jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-javaagent:${visualMockitoAgent.singleFile.absolutePath}")
            })
            it.systemProperty("jmx.visual.root", layout.buildDirectory.dir("qa-v2-search-r4/visual").get().asFile.absolutePath)
            it.systemProperty("jmx.visual.phase", providers.gradleProperty("jmxVisualPhase").getOrElse("disabled"))
            it.systemProperty("jmx.visual.run", providers.gradleProperty("jmxVisualRun").getOrElse(""))
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        doFirst {
            check(releaseSigningReady) {
                "Release signing is not configured. Set JMX_RELEASE_KEYSTORE_FILE, " +
                    "JMX_RELEASE_STORE_PASSWORD, JMX_RELEASE_KEY_ALIAS and JMX_RELEASE_KEY_PASSWORD."
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.gson)
    implementation(libs.miuix.blur.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.nav.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
    implementation(libs.okhttp)
    implementation(libs.opencc4j)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.mockito)
    add(visualMockitoAgent.name, libs.mockito)
}
