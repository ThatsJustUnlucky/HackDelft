plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.hackdelft"
version = "1.0.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.2.5")
    type.set("IC")
    plugins.set(listOf("com.intellij.java"))
}

kotlin {
    jvmToolchain(17)
}

tasks {
    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("243.*")
    }

    runIde {
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
}
