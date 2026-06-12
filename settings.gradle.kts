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

// core はドメインロジック (Android 非依存・JVM 単体テストで検証)、app は Wear OS アプリ、
// spec は公開 surface spec の生成器 (release の semver 自動判定用)、
// server は履歴・設定同期 API (ktor。docs/stock/sync.md)
include(":core", ":app", ":spec", ":server")
