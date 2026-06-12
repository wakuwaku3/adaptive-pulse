import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// app モジュール (Android, jvmTarget 17) から参照されるため bytecode target を合わせる。
// devbox の JDK は 21 なので、java/kotlin 両方を明示しないと target 不一致で弾かれる
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 同期モデル (SessionRecord 等) は watch/phone/server 全員が JSON で交換する
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
