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
  val bungeeVersion = extra.get("mcmetaBungeeCordVersion") as String?
  if (bungeeVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: bungeecord version missing")
  }
  compileOnly("net.md-5:bungeecord-api:$bungeeVersion")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
