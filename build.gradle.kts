import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.composeCompiler)
    alias(libs.plugins.jetbrains.compose)
}

group = "dk.perspektiva.ttsroad.desktop"

// Single version source (gradle.properties -> ttsroad.version). Used for the Gradle/Maven
// coordinate, the jpackage packageVersion, installer filenames, the generated BuildInfo the
// About text reads, and CI release artifacts. There is deliberately no second number.
val appVersionValue: String = providers.gradleProperty("ttsroad.version").get()
val debRevisionValue: String = providers.gradleProperty("ttsroad.debRevision").get()
val appNameValue = "TTSRoad"
version = appVersionValue

require(Regex("[0-9][A-Za-z0-9.+~]*").matches(debRevisionValue)) {
    "ttsroad.debRevision must be a Debian revision such as 1 or 2ubuntu1"
}

val jdkVersion: Int = libs.versions.jdk.get().toInt()

// CI sets -Pttsroad.warningsAsErrors=true so a new Kotlin warning fails the build there
// without making local iteration painful.
val warningsAsErrors: Boolean =
    providers.gradleProperty("ttsroad.warningsAsErrors").orNull?.toBoolean() ?: false

dependencies {
    // Compose Multiplatform desktop (Skia-rendered native UI) + Material 3.
    // `compose.material3` / `compose.materialIconsExtended` are error-level deprecations in
    // CMP 1.11.1, so the artifacts are named explicitly via the version catalog instead.
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.material3)
    implementation(libs.compose.materialIconsExtended)

    // Coroutines with the Swing/AWT main dispatcher used by Compose Desktop.
    implementation(libs.kotlinx.coroutines.swing)

    // Networking — same stack as the Android client so models/api port over.
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // The production playback backend (docs/adr/0002-playback-engine.md). GStreamer itself is a
    // system package, not a bundled one — these are only the bindings, and JNA is pinned here
    // because gst1-java-core's POM asks for the open range [5.2.0,6.0).
    implementation(libs.gst1.java.core)
    implementation(libs.jna)

    // MPRIS over D-Bus (docs/adr/0006-listening-preferences-and-desktop-integration.md). Pure
    // Java: the wire protocol is implemented in dbus-java and the session bus is reached through
    // the JDK's own AF_UNIX SocketChannel, so this adds no native library to the jlink image.
    // Everything behind it is optional at runtime — no D-Bus means no MPRIS, not no playback.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)
    runtimeOnly(libs.slf4j.nop)

    // Pure-JVM MP3 decoding, registered as a javax.sound.sampled SPI. Retained as the fallback
    // engine on machines without GStreamer — Windows and macOS by default — where it keeps the
    // behaviour it has always had, speed control included (i.e. none).
    implementation(libs.soundlibs.mp3spi)
    implementation(libs.soundlibs.jlayer)
    implementation(libs.soundlibs.tritonusShare)

    // Async cover-image loading + in-memory/disk caching (covers are served unauthenticated).
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.junit.jupiter)
    // kotlin("test") resolves to kotlin-test-junit5 automatically because `test` uses the JUnit
    // Platform; its version is the Kotlin plugin's, so it is not a separate catalog entry.
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Compose UI tests use the JUnit 4 `createComposeRule()` API.
    testImplementation(libs.compose.uiTestJunit4)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
}

kotlin {
    // Toolchain, not sourceCompatibility/targetCompatibility: Gradle provisions a real JDK 25
    // (via the foojay resolver in settings.gradle.kts) and uses it for javac, kotlinc, jlink and
    // jpackage. Setting a *mismatched* jvmTarget on top of a toolchain is what caused the earlier
    // "JVM target alignment" breakage, so both are derived from the same catalog entry.
    jvmToolchain(jdkVersion)

    compilerOptions {
        // Explicit bytecode release level, tied to the same single source as the toolchain.
        jvmTarget.set(JvmTarget.fromTarget(jdkVersion.toString()))
        allWarningsAsErrors.set(warningsAsErrors)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // `--release` rather than -source/-target, so linking against newer JDK APIs is a compile error.
    options.release.set(jdkVersion)
}

/**
 * Emits `BuildInfo.kt` so the running app shows the same version the installer was stamped with,
 * instead of a second hand-maintained constant.
 *
 * Written as a real task class rather than a `Sync` of `resources.text.fromString(...)`: that
 * helper stages its content under `build/tmp`, which `clean` wipes, so a single `clean check`
 * invocation would see the generator as NO-SOURCE and fail to compile.
 */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val debRevision: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            |// GENERATED by the `generateBuildInfo` Gradle task. Do not edit.
            |package dk.perspektiva.ttsroad.desktop
            |
            |/** Build-time constants injected from the single `ttsroad.version` Gradle property. */
            |object BuildInfo {
            |    const val VERSION: String = "${appVersion.get()}"
            |    const val APP_NAME: String = "${appName.get()}"
            |    const val DEB_REVISION: String = "${debRevision.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    appVersion.set(appVersionValue)
    appName.set(appNameValue)
    debRevision.set(debRevisionValue)
    outputDir.set(layout.buildDirectory.dir("generated/buildinfo/kotlin"))
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}

// `clean` has no ordering relation to a generator that writes into build/, so in a single
// `clean check` invocation it can otherwise delete BuildInfo.kt after it was generated.
generateBuildInfo.configure { mustRunAfter(tasks.named("clean")) }

/**
 * Two things in this app reach outside the JVM and both are "restricted" on JDK 25: Skiko loads its
 * rendering library with `System::load`, and the Windows credential store links `Advapi32.dll`
 * through `java.lang.foreign`. Without this flag each prints a multi-line warning on first use, and
 * the JVM says such calls will be *blocked* in a future release — so declaring the access now is
 * both quieter and forward-compatible.
 */
val nativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
    // Compose UI tests need a real (or virtual) display; CI runs them under Xvfb.
    systemProperty("java.awt.headless", "false")
    jvmArgs(nativeAccessArgs)
}

/*
 * Phase 5 playback-engine prototype — see docs/adr/0002-playback-engine.md.
 *
 * It gets its own source set on purpose. `main` never sees gst1-java-core, so the shipped app and
 * its jlink image are untouched by an evaluation that has not been accepted yet, and production
 * code cannot import the prototype by accident.
 *
 * `check` *compiles* it so it cannot silently rot, but never runs it: running needs a real
 * GStreamer install, which CI does not have. Run it by hand on a machine that does:
 *
 *     ./gradlew runPlaybackPrototype
 */
val prototype: SourceSet = sourceSets.create("prototype")

dependencies {
    "prototypeImplementation"(libs.gst1.java.core)
    "prototypeImplementation"(libs.jna)
    // The Compose compiler plugin is applied to every Kotlin compilation in the project and
    // refuses to run without the Compose runtime on the compile classpath — even here, where there
    // is no UI at all. compileOnly satisfies it without putting Compose in the prototype's runtime
    // classpath or implying this code draws anything.
    "prototypeCompileOnly"(libs.compose.runtime)
}

tasks.register<JavaExec>("runPlaybackPrototype") {
    group = "verification"
    description = "Measures rate/pitch, time-to-first-audio and seek latency through GStreamer."
    classpath = prototype.runtimeClasspath
    mainClass.set("dk.perspektiva.ttsroad.desktop.prototype.GstPlaybackPrototypeKt")
    // gst1-java-core reaches native code through JNA's System.load — the same restricted-method
    // problem Skiko and the Windows credential store already have, so the same declaration.
    jvmArgs(nativeAccessArgs)
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) },
    )
}

tasks.named("check") { dependsOn(tasks.named("compilePrototypeKotlin")) }

/**
 * jpackage wants a conventional Linux PNG rather than the large source artwork that the Compose
 * window also uses. Deriving it here keeps one checked-in brand asset and makes the package input
 * deterministic on machines without ImageMagick.
 */
abstract class GenerateLinuxPackageIcon : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:Input
    abstract val edgePixels: Property<Int>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val source = ImageIO.read(sourceFile.get().asFile)
            ?: error("Linux package icon is not a readable image")
        require(source.width == source.height) { "Linux package icon must be square" }

        val edge = edgePixels.get()
        val scaled = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
            )
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, edge, edge, null)
        } finally {
            graphics.dispose()
        }
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        check(ImageIO.write(scaled, "png", target)) { "No PNG writer is available in the build JDK" }
    }
}

val generateLinuxPackageIcon = tasks.register<GenerateLinuxPackageIcon>("generateLinuxPackageIcon") {
    sourceFile.set(layout.projectDirectory.file("src/main/composeResources/drawable/ttsroad.png"))
    edgePixels.set(512)
    outputFile.set(layout.buildDirectory.file("generated/package-icons/linux/ttsroad.png"))
}

// jlink/jpackage default to the JVM that is *running Gradle*, not to the Kotlin toolchain, so
// without this the bundled runtime image is a JDK 21 that cannot load our class-file-69 output
// ("UnsupportedClassVersionError ... class file version 69.0"). Resolving the launcher here also
// forces the toolchain to be provisioned before packaging.
val packagingJavaHome: String = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) }
    .get()
    .metadata
    .installationPath
    .asFile
    .absolutePath

compose.resources {
    packageOfResClass = "dk.perspektiva.ttsroad.desktop.resources"
    publicResClass = true
}

compose.desktop {
    application {
        mainClass = "dk.perspektiva.ttsroad.desktop.MainKt"
        javaHome = packagingJavaHome
        jvmArgs += nativeAccessArgs

        buildTypes.release.proguard {
            // CMP 1.11.1's bundled ProGuard 7.7.0 cannot read Java 25 bytecode
            // ("Unsupported version number [69.0]"). 7.8.0 was the first release that can. Native
            // installers do not require shrinking, and this app has reflection/service-provider
            // boundaries in Moshi, Retrofit, JNA, GStreamer, D-Bus and mp3spi. Keep the release
            // image behaviorally identical until explicit, tested keep rules cover all of them.
            version.set(libs.versions.proguard)
            isEnabled.set(false)
        }

        nativeDistributions {
            // Real OS installers via jpackage; the JRE is bundled, so end users
            // don't need Java installed.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = appNameValue
            // Same single source as `version` above — installer filenames and the About text
            // can no longer drift apart. jpackage requires MAJOR > 0 (esp. for the macOS .dmg).
            packageVersion = appVersionValue
            copyright = "Copyright 2026 Perspektiva"
            description = "Listen to and read along with audiobooks from a TTSRoad server"
            vendor = "Perspektiva"
            licenseFile.set(layout.projectDirectory.file("packaging/linux/LICENSE.txt"))

            // The jlink image is minimised by module inference; these are the modules the app
            // needs reflectively/at runtime and which inference does not always find:
            //   java.desktop  — AWT/Swing + the javax.sound.sampled MP3 SPI
            //   java.naming   — OkHttp's DNS/JNDI usage
            //   jdk.crypto.ec — TLS elliptic-curve suites (https:// servers fail without it)
            //   java.management / jdk.unsupported — Kotlin + Skiko runtime bits
            //   jdk.security.auth — com.sun.security.auth.module.UnixSystem, which dbus-java's
            //     unix-socket transport uses to read the uid when connecting to the session bus.
            //     Reached only by reflection, so inference misses it and MPRIS degrades to "no
            //     session bus" in the packaged image while working fine from source.
            modules(
                "java.desktop",
                "java.instrument",
                "java.naming",
                "java.management",
                "jdk.accessibility",
                "jdk.crypto.ec",
                "jdk.security.auth",
                "jdk.unsupported",
            )

            windows {
                // Without these the MSI installs with no Start menu / desktop entry at all.
                menu = true
                shortcut = true
                menuGroup = "TTSRoad"
                // Stable across releases so newer MSIs upgrade in place instead of
                // installing side by side.
                upgradeUuid = "9c3f5a1e-7d42-4b8a-b6f0-2f1a44e6d9c3"
            }
            linux {
                shortcut = true
                // Debian package names are lowercase even though the application, launcher and
                // desktop display name stay TTSRoad.
                packageName = "ttsroad"
                appRelease = debRevisionValue
                appCategory = "AudioVideo;Audio;"
                debMaintainer = "TTSRoad Maintainers <jonarihen@users.noreply.github.com>"
                menuGroup = "Audio & Video"
                iconFile.set(generateLinuxPackageIcon.flatMap { it.outputFile })
            }
            macOS {
                bundleID = "dk.perspektiva.ttsroad.desktop"
            }
        }
    }
}

// Compose 1.11 exposes most Linux jpackage options through its DSL, but not package dependencies.
// `freeArgs` is the plugin's supported escape hatch to the underlying JDK tool. The control
// package is finalized below with Debian's own tooling because Compose clears its private
// jpackage resource directory immediately before launch, preventing a stable control/desktop
// template override. Response-file values cannot contain unquoted spaces, hence the comma-only
// dependency list.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) {
        freeArgs.addAll(
            "--linux-package-deps",
            listOf(
                "gstreamer1.0-plugins-base",
                "gstreamer1.0-plugins-good",
                "gstreamer1.0-pulseaudio",
                "libsecret-tools",
            ).joinToString(","),
        )
    }
}

// jpackage creates the native archive and dpkg-deb performs the small Debian-specific finishing
// pass: Section/Recommends, the Debian copyright file, and desktop fields jpackage cannot express.
// Finalizers are used so the familiar packageDeb/packageReleaseDeb tasks always leave a complete
// artifact. They no-op when a failed jpackage invocation produced no archive.
fun registerDebFinalizer(taskName: String, variantDirectory: String) {
    val debDirectory = layout.buildDirectory.dir("compose/binaries/$variantDirectory/deb")
    val finalizer = tasks.register<Exec>("finalize${taskName.replaceFirstChar(Char::uppercaseChar)}") {
        commandLine(
            "bash",
            layout.projectDirectory.file("packaging/linux/finalize-deb.sh").asFile.absolutePath,
            debDirectory.get().asFile.absolutePath,
            appVersionValue,
            debRevisionValue,
        )
        onlyIf { debDirectory.get().asFile.isDirectory }
    }
    tasks.matching { it.name == taskName }.configureEach { finalizedBy(finalizer) }
}

registerDebFinalizer("packageDeb", "main")
registerDebFinalizer("packageReleaseDeb", "main-release")
