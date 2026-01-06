# mcmeta

Published metadata per Minecraft version lives on branches `mc/<mcVersion>`.
This repository is updated by the harvester action in `uebliche/mcmeta-harvest`.

Two convenience branches exist:

- `latest` (newest release from Mojang manifest)
- `latest-snapshot` (newest non-release, including snapshots/betas)

Proxy branches (Velocity):

- `proxy/velocity-<api>`
- `proxy/velocity-latest`

## Web UI

The interactive browser is built with Vue + Vite. Source lives in `web/` and the
static build is deployed to the `gh-pages` branch by GitHub Actions.

Local dev:

```sh
npm install
npm run dev
```

Build for Pages:

```sh
npm run build
```

GitHub Pages should point to the `gh-pages` branch and `/` folder.

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

### Publishing (Gradle Plugin Portal)

The GitHub Action `Publish Gradle plugin` publishes to the Gradle Plugin Portal
when these secrets are set on the repository:

- `GRADLE_PUBLISH_KEY`
- `GRADLE_PUBLISH_SECRET`

The workflow auto-computes `MCMETA_PLUGIN_VERSION` from the date + commit hash.

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
