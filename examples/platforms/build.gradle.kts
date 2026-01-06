import net.uebliche.mcmeta.McmetaExtension

plugins {
  id("net.uebliche.mcmeta") apply false
}

val mcVersion = providers.gradleProperty("mcmeta.minecraftVersion").orElse("1.21.4")

subprojects {
  group = "net.uebliche.mcmeta.example"
  version = "0.1.0"

  repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
  }

  apply(plugin = "net.uebliche.mcmeta")
  extensions.configure<McmetaExtension>("mcmeta") {
    minecraftVersion = mcVersion.get()
    enableManifoldPreprocessor = false
  }
}
