plugins {
  java
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
  val paperVersion = extra.get("mcmetaPaperVersion") as String?
  if (paperVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: paper version missing")
  }
  compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
