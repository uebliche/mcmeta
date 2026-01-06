plugins {
  java
}

dependencies {
  implementation(project(":common"))

  val extra = project.extensions.extraProperties
  val forgeVersion = extra.get("mcmetaForgeVersion") as String?
  if (forgeVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: forge version missing")
  }
  compileOnly("net.minecraftforge:forge:$forgeVersion")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
