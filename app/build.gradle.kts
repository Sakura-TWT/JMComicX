import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseKeystoreFile = providers.environmentVariable("PRISMIA_RELEASE_KEYSTORE_FILE")
    .orElse("signing/private/prismia-plus-release.keystore")
    .get()
    .let(::file)
val releaseStorePassword = providers.environmentVariable("PRISMIA_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PRISMIA_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PRISMIA_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = releaseKeystoreFile.isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

@Suppress("UnstableApiUsage")
android {
    // Installed Android identity is `as.prismia.plus`; source namespace stays
    // readable as `app.prismia.plus` while remaining compatible with Kotlin.
    namespace = "app.prismia.plus"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "as.prismia.plus"
        minSdk = 33
        targetSdk = 37
        versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("prismiaRelease") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("prismiaRelease")
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
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        doFirst {
            check(releaseSigningReady) {
                "Release signing is not configured. Set PRISMIA_RELEASE_KEYSTORE_FILE, " +
                    "PRISMIA_RELEASE_STORE_PASSWORD, PRISMIA_RELEASE_KEY_ALIAS and PRISMIA_RELEASE_KEY_PASSWORD."
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":core:foundation"))
    implementation(project(":core:media"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":domain:comic"))
    implementation(project(":domain:video"))
    implementation(project(":feature:app-shell"))
    implementation(project(":source:jmcomic"))
    implementation(project(":source:oreno"))
    implementation(project(":source:iwara"))
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
}
