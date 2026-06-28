import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "dk.perspektiva.ttsroad.desktop"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Compose Multiplatform desktop (Skia-rendered native UI) + Material 3.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines with the Swing/AWT main dispatcher used by Compose Desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Networking — same stack as the Android client so models/api port over.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

compose.desktop {
    application {
        mainClass = "dk.perspektiva.ttsroad.desktop.MainKt"

        nativeDistributions {
            // Real OS installers via jpackage; the JRE is bundled, so end users
            // don't need Java installed.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            // jpackage requires MAJOR > 0 (esp. for the macOS .dmg).
            packageName = "TTSRoad"
            packageVersion = "1.0.0"
            description = "TTSRoad desktop client"
            vendor = "Perspektiva"
        }
    }
}
