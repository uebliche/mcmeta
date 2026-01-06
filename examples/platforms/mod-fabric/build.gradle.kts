plugins {
  java
}

dependencies {
  implementation(project(":common"))

  val extra = project.extensions.extraProperties
  val loader = extra.get("mcmetaFabricLoaderVersion") as String?
  val fabricApi = extra.get("mcmetaFabricApiVersion") as String?
  if (loader.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: fabric loader version missing")
  }
  compileOnly("net.fabricmc:fabric-loader:$loader")
  if (!fabricApi.isNullOrBlank()) {
    compileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApi")
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
