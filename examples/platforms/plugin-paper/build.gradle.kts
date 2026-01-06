plugins {
  java
}

dependencies {
  implementation(project(":common"))

  val extra = project.extensions.extraProperties
  val mcVersion = providers.gradleProperty("mcmeta.minecraftVersion").orElse("1.21.4").get()
  val paperVersion = extra.get("mcmetaPaperVersion") as String?
  val resolved = when {
    paperVersion.isNullOrBlank() -> "${mcVersion}-R0.1-SNAPSHOT"
    paperVersion.contains("-") -> paperVersion
    else -> "${mcVersion}-R0.1-SNAPSHOT"
  }
  compileOnly("io.papermc.paper:paper-api:$resolved")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
