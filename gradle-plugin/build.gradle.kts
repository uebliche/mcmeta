plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "1.9.24"
}

group = "info.uebliche"
version = "0.1.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation("com.google.code.gson:gson:2.11.0")
}

gradlePlugin {
  plugins {
    create("mcmeta") {
      id = "info.uebliche.mcmeta"
      implementationClass = "io.uebliche.mcmeta.McmetaPlugin"
      displayName = "mcmeta version helper"
      description = "Loads mcmeta versions into Gradle properties."
    }
  }
}

kotlin {
  jvmToolchain(11)
}
