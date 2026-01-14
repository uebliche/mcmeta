plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.2.0"
  `maven-publish`
  id("com.gradle.plugin-publish") version "1.2.1" apply false
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

val publishEnabled = providers.gradleProperty("mcmetaPublish").isPresent ||
  (System.getenv("MCMETA_PUBLISH") == "1")

if (publishEnabled) {
  apply(plugin = "com.gradle.plugin-publish")
  val pluginBundle = extensions.findByName("pluginBundle")
  if (pluginBundle is groovy.lang.GroovyObject) {
    pluginBundle.setProperty("website", "https://github.com/uebliche/mcmeta")
    pluginBundle.setProperty("vcsUrl", "https://github.com/uebliche/mcmeta")
    pluginBundle.setProperty("tags", listOf("minecraft", "modding", "versions", "metadata"))
  }
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
