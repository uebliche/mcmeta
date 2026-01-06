pluginManagement {
  includeBuild("../../gradle-plugin")
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
  }
  val fabricLoomVersion = providers.gradleProperty("fabricLoomVersion").orElse("1.6.12").get()
  val forgeGradleVersion = providers.gradleProperty("forgeGradleVersion").orElse("6.0.29").get()
  plugins {
    id("fabric-loom") version fabricLoomVersion
    id("net.minecraftforge.gradle") version forgeGradleVersion
  }
}

rootProject.name = "mcmeta-platform-examples"
include(":common")
include(":mod-fabric")
include(":mod-forge")
include(":plugin-paper")
include(":plugin-velocity")
include(":plugin-bungeecord")
