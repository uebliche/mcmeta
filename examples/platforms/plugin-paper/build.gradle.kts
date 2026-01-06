plugins {
  java
}

dependencies {
  implementation(project(":common"))
}

afterEvaluate {
  val extra = rootProject.extensions.extraProperties
  val paperVersion = extra.get("mcmetaPaperVersion") as String?
  if (paperVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: paper version missing")
  }
  dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
