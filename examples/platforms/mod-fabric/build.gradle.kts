plugins {
  id("fabric-loom")
  java
}

val mcVersion = providers.gradleProperty("mcmeta.minecraftVersion").orElse("1.21.4")

dependencies {
  minecraft("com.mojang:minecraft:${mcVersion.get()}")
  mappings(loom.officialMojangMappings())
  implementation(project(":common"))
}

loom {
  splitEnvironmentSourceSets()
}

afterEvaluate {
  val extra = project.extensions.extraProperties
  val loader = extra.get("mcmetaFabricLoaderVersion") as String?
  val fabricApi = extra.get("mcmetaFabricApiVersion") as String?
  if (loader.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: fabric loader version missing")
  }
  dependencies {
    modImplementation("net.fabricmc:fabric-loader:$loader")
    if (!fabricApi.isNullOrBlank()) {
      modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
    }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
