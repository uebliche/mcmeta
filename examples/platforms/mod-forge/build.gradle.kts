plugins {
  id("net.minecraftforge.gradle")
  java
}

val mcVersion = providers.gradleProperty("mcmeta.minecraftVersion").orElse("1.21.4")

minecraft {
  mappings("official", mcVersion.get())
}

// Trigger mcmeta resolution before dependencies are resolved.
val mcmetaResolver = project.tasks.named("mcmetaResolve")

configurations.configureEach {
  if (isCanBeResolved) {
    incoming.beforeResolve {
      mcmetaResolver.get().execute()
    }
  }
}

dependencies {
  implementation(project(":common"))

  val extra = project.extensions.extraProperties
  val forgeVersion = extra.get("mcmetaForgeVersion") as String?
  if (forgeVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: forge version missing")
  }
  implementation("net.minecraftforge:forge:$forgeVersion")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
