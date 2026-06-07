import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.hackdelft"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community 2025.2.1 — matches the locally installed IDE.
        create("IC", "2025.2.1")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            // Leave the upper bound open so the plugin keeps loading in newer IDEs.
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Pass the local .env OPENAI_API_KEY into the sandbox IDE as -Dopenai.api.key (read by AIExplainService).
tasks.withType<RunIdeTask>().configureEach {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .firstOrNull { it.startsWith("OPENAI_API_KEY=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> jvmArgs("-Dopenai.api.key=$key") }
    }
}
