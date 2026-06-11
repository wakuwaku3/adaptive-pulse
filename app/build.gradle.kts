import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.wakuwaku3.adaptivepulse"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wakuwaku3.adaptivepulse"
        minSdk = 30 // Wear OS 3 (Pixel Watch 初代) 以上
        targetSdk = 35
        // リリースは release workflow が自動採番して環境変数で渡す (semver から導出)。
        // ローカルビルドは dev 固定で、Play へ上げる versionCode と衝突しない
        versionCode = System.getenv("ADAPTIVE_PULSE_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("ADAPTIVE_PULSE_VERSION_NAME") ?: "0.0.0-dev"
    }

    // Play 内部テスト配布用の upload key。keystore は ~/keystores/ (コミット禁止)、
    // 資格情報は .env (direnv) の環境変数。未設定なら署名なしでビルドする (CI はこちら)
    val keystorePath = System.getenv("ADAPTIVE_PULSE_KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ADAPTIVE_PULSE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ADAPTIVE_PULSE_KEY_ALIAS")
                keyPassword = System.getenv("ADAPTIVE_PULSE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.health.services.client)
    // Health Services の ListenableFuture を suspend で扱う
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    // 画面オフ中もセッション継続を示す Ongoing Activity
    implementation(libs.wear.ongoing)
    implementation(libs.androidx.lifecycle.service)
    // ウォッチフェイスから 1 スワイプで開始するタイル
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
}
