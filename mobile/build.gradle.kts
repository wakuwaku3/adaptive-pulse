import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// google-services.json は Firebase プロジェクトに紐づく秘匿性の低い設定ファイルだが、
// 各自の環境で配置する運用にし、無くてもビルドできるようプラグインを条件適用する
// (docs/stock/setup-firebase.md)
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "io.github.wakuwaku3.adaptivepulse.mobile"
    compileSdk = 35

    defaultConfig {
        // watch と同一 applicationId = Play では同一アプリの phone フォームファクター
        applicationId = "io.github.wakuwaku3.adaptivepulse"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("ADAPTIVE_PULSE_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("ADAPTIVE_PULSE_VERSION_NAME") ?: "0.0.0-dev"
    }

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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // Google サインイン (Firebase Auth + Credential Manager)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    // 履歴・設定の同期 (Firestore に直接書き込む。docs/stock/sync.md)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.serialization.json)

    // watch との同期 (Wearable Data Layer)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.fragment)

    implementation(libs.androidx.datastore.preferences)

    // Health Connect から日次の健康指標 (心拍/体重/食事 etc.) を取り込み JSON で書き出す
    implementation(libs.androidx.health.connect.client)
    // アプリ未起動でも初回 back-fill と日次同期を走らせる
    implementation(libs.androidx.work.runtime.ktx)
}
