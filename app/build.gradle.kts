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
        versionCode = 1
        versionName = "0.1.0"
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
}
