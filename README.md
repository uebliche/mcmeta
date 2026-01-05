# mcmeta

Published metadata per Minecraft version lives on branches `mc/<mcVersion>`.
This repository is updated by the harvester action in `uebliche/mcmeta-harvest`.

## Web UI

The interactive browser is built with Vue + Vite. Source lives in `web/` and the
static build is emitted into `docs/` for GitHub Pages.

Local dev:

```sh
npm install
npm run dev
```

Build for Pages:

```sh
npm run build
```

GitHub Pages should point to the `main` branch and `/docs` folder.

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
  id("info.uebliche.mcmeta")
}

mcmeta {
  minecraftVersion = "1.21.4"
}

dependencies {
  val fabricLoader = extra["mcmetaFabricLoaderVersion"] as String?
  // use versions in your dependencies
}
```
