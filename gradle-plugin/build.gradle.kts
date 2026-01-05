plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "1.9.24"
  id("com.gradle.plugin-publish") version "1.2.1"
}

group = "net.uebliche"
version = System.getenv("MCMETA_PLUGIN_VERSION") ?: "0.1.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation("com.google.code.gson:gson:2.11.0")
}

gradlePlugin {
  plugins {
    create("mcmeta") {
      id = "net.uebliche.mcmeta"
      implementationClass = "net.uebliche.mcmeta.McmetaPlugin"
      displayName = "mcmeta version helper"
      description = "Loads mcmeta versions into Gradle properties."
    }
  }
}

pluginBundle {
  website = "https://github.com/uebliche/mcmeta"
  vcsUrl = "https://github.com/uebliche/mcmeta"
  tags = listOf("minecraft", "modding", "versions", "metadata")
}

kotlin {
  jvmToolchain(11)
}
