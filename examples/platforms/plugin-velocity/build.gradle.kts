plugins {
  java
}

dependencies {
  implementation(project(":common"))

  val extra = project.extensions.extraProperties
  val velocityVersion = extra.get("mcmetaVelocityVersion") as String?
  if (velocityVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: velocity version missing")
  }
  compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
