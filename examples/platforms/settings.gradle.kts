pluginManagement {
  includeBuild("../../gradle-plugin")
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "mcmeta-platform-examples"
include(":common")
include(":mod-fabric")
include(":mod-forge")
include(":plugin-paper")
include(":plugin-velocity")
include(":plugin-bungeecord")
