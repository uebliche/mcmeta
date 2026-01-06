plugins {
  id("net.minecraftforge.gradle")
  java
}

val mcVersion = providers.gradleProperty("mcmeta.minecraftVersion").orElse("1.21.4")

minecraft {
  mappings("official", mcVersion.get())
}

dependencies {
  implementation(project(":common"))
}

afterEvaluate {
  val extra = rootProject.extensions.extraProperties
  val forgeVersion = extra.get("mcmetaForgeVersion") as String?
  if (forgeVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: forge version missing")
  }
  dependencies {
    implementation("net.minecraftforge:forge:$forgeVersion")
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
