pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "Prismia"
include(":core")
include(":core:foundation")
include(":core:media")
include(":core:designsystem")
include(":core:data")
include(":domain:comic")
include(":domain:video")
include(":feature:app-shell")
include(":source:jmcomic")
include(":source:oreno")
include(":source:iwara")
include(":app")
