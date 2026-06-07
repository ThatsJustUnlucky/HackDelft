rootProject.name = "codesidian"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 21 toolchain (neither locally installed JDK is 21).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
