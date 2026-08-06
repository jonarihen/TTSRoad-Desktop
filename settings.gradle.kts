rootProject.name = "TTSRoad-Desktop"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Downloads a matching JDK when the toolchain requested in build.gradle.kts is not installed
// locally, so a fresh checkout only needs *some* JDK on PATH to bootstrap Gradle itself.
// The version is a literal because the version catalog is not yet available in this block;
// gradle/libs.versions.toml carries the same number under `foojayResolver` for bookkeeping.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // The JetBrains "compose/dev" repository is deliberately absent: the entire dependency graph
    // for Compose Multiplatform 1.11.1 resolves from google() + mavenCentral(). google() is
    // mandatory though — CMP 1.11 depends on real androidx artifacts (compose-runtime,
    // collection, lifecycle, savedstate) even for a desktop-only JVM build.
    repositories {
        google()
        mavenCentral()
    }
}
