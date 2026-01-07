# mcmeta

Published metadata per Minecraft version lives on branches `mc/<mcVersion>`.
This repository is updated by the harvester action in `uebliche/mcmeta-harvest`.

Two convenience branches exist:

- `latest` (newest release from Mojang manifest)
- `latest-snapshot` (newest non-release, including snapshots/betas)

Proxy branches (Velocity):

- `proxy/velocity-<api>`
- `proxy/velocity-latest`

Proxy branches (BungeeCord):

- `proxy/bungeecord-<api>`
- `proxy/bungeecord-latest`

## Web viewer

The web viewer now lives in the uebliche docs under `/mcmeta/viewer`.

## Gradle plugin (local include)

A small Gradle plugin is available in `gradle-plugin/`. It loads mcmeta
versions and exposes them as Gradle extra properties.

Example `settings.gradle.kts`:

```kotlin
pluginManagement {
  includeBuild("gradle-plugin")
}
```

Example `build.gradle.kts`:

```kotlin
plugins {
  id("net.uebliche.mcmeta")
}

mcmeta {
  minecraftVersion = "1.21.4"
}

dependencies {
  val fabricLoader = extra["mcmetaFabricLoaderVersion"] as String?
  val paperBuild = extra["mcmetaPaperVersion"] as String?
  val velocityVersion = extra["mcmetaVelocityVersion"] as String?
  val foliaBuild = extra["mcmetaFoliaVersion"] as String?
  // use versions in your dependencies
}
```

### Example project

Platform examples live in:

- `examples/platforms`

### Manifold preprocessor (optional)

Enable Manifold preprocessor support to get numeric symbols for all Mojang
versions (including snapshots) and a `MC_VER` value for the requested
Minecraft version. This allows expressions like:

```java
#if MC_VER >= MC_1_20_5
// code for 1.20.5+
#endif
```

Gradle example:

```kotlin
mcmeta {
  minecraftVersion = "1.21.4"
  enableManifoldPreprocessor = true
  // optional override
  // manifoldPreprocessorVersion = "2025.1.22"
}
```

### Publishing (GitHub Packages)

The same workflow also publishes to GitHub Packages:

- `https://maven.pkg.github.com/uebliche/mcmeta`
- Uses `GITHUB_TOKEN` with `packages:write` permission.

To consume via Gradle Plugin DSL, add the repo in `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    maven {
      url = uri("https://maven.pkg.github.com/uebliche/mcmeta")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
    gradlePluginPortal()
  }
}
```
