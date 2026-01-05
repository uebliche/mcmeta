plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "1.9.24"
  `maven-publish`
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

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/uebliche/mcmeta")
      credentials {
        username = System.getenv("GITHUB_ACTOR") ?: ""
        password = System.getenv("GITHUB_TOKEN") ?: ""
      }
    }
  }
}

kotlin {
  jvmToolchain(11)
}
