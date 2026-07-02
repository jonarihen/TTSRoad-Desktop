import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.8.2"
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

    // Pure-JVM MP3 decoding, registered as a javax.sound.sampled SPI so chapter audio
    // (downloaded with the bearer auth header attached) can be played via SourceDataLine
    // without an external native player like VLC.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")

    // Async cover-image loading + in-memory/disk caching (covers are served unauthenticated).
    // Pinned to 3.2.0 (not the latest 3.5.0): newer releases bump their kotlin-stdlib dependency
    // past what our Kotlin 2.1.0 compiler's metadata reader accepts.
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
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
            packageVersion = "1.0.1"
            description = "TTSRoad desktop client"
            vendor = "Perspektiva"

            windows {
                // Without these the MSI installs with no Start menu / desktop entry at all.
                menu = true
                shortcut = true
                menuGroup = "TTSRoad"
                // Stable across releases so newer MSIs upgrade in place instead of
                // installing side by side.
                upgradeUuid = "9c3f5a1e-7d42-4b8a-b6f0-2f1a44e6d9c3"
            }
        }
    }
}
