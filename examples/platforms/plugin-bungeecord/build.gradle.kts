plugins {
  java
}

dependencies {
  implementation(project(":common"))
}

afterEvaluate {
  val extra = project.extensions.extraProperties
  val bungeeVersion = extra.get("mcmetaBungeeCordVersion") as String?
  if (bungeeVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: bungeecord version missing")
  }
  dependencies {
    compileOnly("net.md-5:bungeecord-api:$bungeeVersion")
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
