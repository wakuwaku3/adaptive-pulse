pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "adaptive-pulse"

// core はドメインロジック (Android 非依存・JVM 単体テストで検証)、app は Wear OS アプリ
include(":core", ":app")
