plugins {
  java
}

dependencies {
  implementation(project(":common"))
}

afterEvaluate {
  val extra = project.extensions.extraProperties
  val velocityVersion = extra.get("mcmetaVelocityVersion") as String?
  if (velocityVersion.isNullOrBlank()) {
    throw IllegalStateException("mcmeta: velocity version missing")
  }
  dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}
