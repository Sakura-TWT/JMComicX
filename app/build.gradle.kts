import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.coil.compose)
    implementation(libs.gson)
    implementation(libs.miuix.blur.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.navigation3.ui.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
    implementation(libs.okhttp)
    implementation(libs.opencc4j)

    testImplementation(libs.junit)
}
