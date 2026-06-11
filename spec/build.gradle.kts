import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

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
    implementation(project(":core"))
    // 公開 surface の列挙に必要。アプリ本体に reflect を持ち込まないため別モジュールにしている
    implementation(kotlin("reflect"))
}

// 公開 surface spec を出力する (release workflow の semver 自動判定が使う)
tasks.register<JavaExec>("emitSpec") {
    group = "release"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.wakuwaku3.adaptivepulse.spec.SpecMainKt")
    args(
        rootProject.file("app/src/main/AndroidManifest.xml").absolutePath,
        layout.buildDirectory.file("spec.json").get().asFile.absolutePath,
    )
}
