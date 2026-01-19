package net.uebliche.mcmeta

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

private const val DEFAULT_REPO = "uebliche/mcmeta"
private const val DEFAULT_RAW_BASE = "https://raw.githubusercontent.com"
private const val DEFAULT_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

open class McmetaExtension {
  var minecraftVersion: String = ""
  var repo: String = DEFAULT_REPO
  var rawBase: String = DEFAULT_RAW_BASE
  var manifestUrl: String = DEFAULT_MANIFEST
  var loomBranch: String = "loom/latest"
  var cacheMinutes: Long = 30
  var autoLoad: Boolean = true
  var enableManifoldPreprocessor: Boolean = false
  var manifoldPreprocessorVersion: String? = null
  val repositories = McmetaRepositories()
  val dependencies = McmetaDependencies()

  fun resolveNow(project: Project) {
    McmetaResolver(project, this).resolve()
    applyOptions(project, this)
  }

  fun repositories(action: Action<McmetaRepositories>) {
    action.execute(repositories)
  }

  fun dependencies(action: Action<McmetaDependencies>) {
    action.execute(dependencies)
  }
}

class McmetaPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("mcmeta", McmetaExtension::class.java)

    project.afterEvaluate {
      if (extension.autoLoad) {
        val resolver = McmetaResolver(project, extension)
        resolver.resolve()
      }
      applyOptions(project, extension)
    }

    project.tasks.register("mcmetaResolve") {
      it.group = "mcmeta"
      it.description = "Loads mcmeta versions into project properties."
      it.doLast {
        if (extension.minecraftVersion.isBlank()) {
          throw IllegalStateException("mcmeta.minecraftVersion must be set")
        }
        McmetaResolver(project, extension).resolve()
        applyOptions(project, extension)
      }
    }
  }
}

open class McmetaRepositories {
  var enabled: Boolean = false
  var includeMavenCentral: Boolean = true
  var fabric: Boolean = false
  var neoforged: Boolean = false
  var paper: Boolean = false
  var velocity: Boolean = false
  var velocitySnapshots: Boolean = false

  fun all() {
    enabled = true
    includeMavenCentral = true
    fabric = true
    neoforged = true
    paper = true
    velocity = true
    velocitySnapshots = true
  }
}

open class McmetaDependencyConfigurations {
  var fabricLoader: String = "modImplementation"
  var fabricApi: String = "modImplementation"
  var neoForge: String = "implementation"
  var paperApi: String = "compileOnly"
  var velocityApi: String = "compileOnly"
  var velocityAnnotationProcessor: String = "annotationProcessor"
}

open class McmetaDependencies {
  var enabled: Boolean = false
  var fabricLoader: Boolean = false
  var fabricApi: Boolean = false
  var neoForge: Boolean = false
  var paperApi: Boolean = false
  var velocityApi: Boolean = false
  var velocityAnnotationProcessor: Boolean = false
  val configurations = McmetaDependencyConfigurations()
  var paperApiVersion: String? = null
  var velocityApiVersion: String? = null

  fun all() {
    enabled = true
    fabricLoader = true
    fabricApi = true
    neoForge = true
    paperApi = true
    velocityApi = true
    velocityAnnotationProcessor = true
  }

  fun configurations(action: Action<McmetaDependencyConfigurations>) {
    action.execute(configurations)
  }
}

private fun applyOptions(project: Project, extension: McmetaExtension) {
  configureRepositories(project, extension.repositories)
  configureDependencies(project, extension)
}

private fun configureRepositories(project: Project, options: McmetaRepositories) {
  if (!options.enabled) return
  if (options.includeMavenCentral) {
    project.repositories.mavenCentral()
  }
  if (options.fabric) {
    addRepo(project, "Fabric", "https://maven.fabricmc.net/")
  }
  if (options.neoforged) {
    addRepo(project, "NeoForged", "https://maven.neoforged.net/releases")
  }
  if (options.paper) {
    addRepo(project, "PaperMC", "https://repo.papermc.io/repository/maven-public/")
  }
  if (options.velocity) {
    addRepo(project, "Velocity", "https://repo.velocitypowered.com/releases/") { repo ->
      repo.content { it.includeGroup("com.velocitypowered") }
    }
  }
  if (options.velocitySnapshots) {
    addRepo(project, "VelocitySnapshots", "https://repo.velocitypowered.com/snapshots/") { repo ->
      repo.content { it.includeGroup("com.velocitypowered") }
    }
  }
}

private fun addRepo(
  project: Project,
  name: String,
  url: String,
  configure: (MavenArtifactRepository) -> Unit = {},
) {
  val existing = project.repositories.filterIsInstance<MavenArtifactRepository>()
    .any { it.url.toString().trimEnd('/') == url.trimEnd('/') }
  if (existing) return
  val repo = project.repositories.maven { maven ->
    maven.name = name
    maven.url = project.uri(url)
  }
  configure(repo)
}

private fun configureDependencies(project: Project, extension: McmetaExtension) {
  val options = extension.dependencies
  if (!options.enabled) return
  if (!ensureResolved(project, extension)) return

  if (options.fabricLoader) {
    val version = extraString(project, "mcmetaFabricLoaderVersion")
    addDependency(project, options.configurations.fabricLoader, "net.fabricmc:fabric-loader:$version", "fabric-loader")
  }
  if (options.fabricApi) {
    val version = extraString(project, "mcmetaFabricApiVersion")
    addDependency(project, options.configurations.fabricApi, "net.fabricmc.fabric-api:fabric-api:$version", "fabric-api")
  }
  if (options.neoForge) {
    val version = extraString(project, "mcmetaNeoForgeVersion")
    addDependency(project, options.configurations.neoForge, "net.neoforged:neoforge:$version", "neoforge")
  }
  if (options.paperApi) {
    val version = options.paperApiVersion?.takeIf { it.isNotBlank() }
      ?: "${extension.minecraftVersion}-R0.1-SNAPSHOT"
    addDependency(project, options.configurations.paperApi, "io.papermc.paper:paper-api:$version", "paper-api")
  }
  if (options.velocityApi) {
    val version = options.velocityApiVersion?.takeIf { it.isNotBlank() }
      ?: extraString(project, "mcmetaVelocityVersion")
    addDependency(project, options.configurations.velocityApi, "com.velocitypowered:velocity-api:$version", "velocity-api")
  }
  if (options.velocityAnnotationProcessor) {
    val version = options.velocityApiVersion?.takeIf { it.isNotBlank() }
      ?: extraString(project, "mcmetaVelocityVersion")
    addDependency(
      project,
      options.configurations.velocityAnnotationProcessor,
      "com.velocitypowered:velocity-api:$version",
      "velocity-api (annotation processor)"
    )
  }
}

private fun ensureResolved(project: Project, extension: McmetaExtension): Boolean {
  val extra = project.extensions.extraProperties
  if (extra.has("mcmetaMinecraftVersion")) return true
  McmetaResolver(project, extension).resolve()
  return extra.has("mcmetaMinecraftVersion")
}

private fun extraString(project: Project, key: String): String? {
  val extra = project.extensions.extraProperties
  if (!extra.has(key)) return null
  val value = extra.get(key)
  return value?.toString()
}

private fun addDependency(project: Project, configuration: String, notation: String, label: String) {
  if (notation.endsWith(":null") || notation.endsWith(":")) {
    project.logger.warn("mcmeta: ${label} version missing; skipping dependency")
    return
  }
  val config = project.configurations.findByName(configuration)
  if (config == null) {
    project.logger.warn("mcmeta: configuration '${configuration}' missing; skipping ${label}")
    return
  }
  project.dependencies.add(configuration, notation)
}

private class McmetaResolver(
  private val project: Project,
  private val extension: McmetaExtension,
) {
  private val gson = Gson()
  private val cacheDir = File(project.gradle.gradleUserHomeDir, "caches/mcmeta").apply { mkdirs() }

  fun resolve() {
    val resolvedVersion = resolveRequestedVersion()
    val sanitized = sanitizeVersion(resolvedVersion)
    if (sanitized.isBlank()) {
      throw IllegalStateException("mcmeta: invalid minecraftVersion")
    }
    if (extension.minecraftVersion.isBlank()) {
      extension.minecraftVersion = resolvedVersion
    }

    val cacheFile = File(cacheDir, "$sanitized.json")

    var payload: String? = if (cacheFile.exists()) cacheFile.readText() else null
    val expired = cacheFile.exists() && isExpired(cacheFile)
    if (payload == null || expired) {
      try {
        val data = fetchData(sanitized)
        cacheFile.writeText(data)
        payload = data
      } catch (err: Exception) {
        if (payload != null) {
          project.logger.warn("mcmeta: fetch failed, using cached data: ${err.message}")
        } else {
          throw IllegalStateException("mcmeta: unable to fetch data and no cache available", err)
        }
      }
    }

    val bundle = gson.fromJson(payload, McmetaBundle::class.java)
    val loaderIndex = gson.fromJson(gson.toJson(bundle.loaderIndex), LoaderIndex::class.java)
    val artifacts = gson.fromJson(gson.toJson(bundle.artifacts), Artifacts::class.java)
    val meta = gson.fromJson(gson.toJson(bundle.meta), MetaV1::class.java)
    applyBundle(loaderIndex, artifacts, meta, sanitized)
    applyLoom(fetchLoomIndex())
    if (extension.enableManifoldPreprocessor) {
      configureManifoldPreprocessor(artifacts, extension.minecraftVersion)
    }
  }

  private fun resolveRequestedVersion(): String {
    val requested = extension.minecraftVersion.trim()
    if (requested.isNotEmpty()) {
      return requested
    }
    val manifest = loadManifest() ?: throw IllegalStateException(
      "mcmeta: minecraftVersion not set and manifest unavailable"
    )
    val latestRelease = manifest.latest?.release
    if (!latestRelease.isNullOrBlank()) {
      project.logger.lifecycle("mcmeta: minecraftVersion not set; using latest release ${latestRelease}")
      return latestRelease
    }
    val fallback = manifest.versions
      ?.mapNotNull { it.id }
      ?.firstOrNull { it.matches(Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?$")) }
    if (!fallback.isNullOrBlank()) {
      project.logger.lifecycle("mcmeta: minecraftVersion not set; using latest stable ${fallback}")
      return fallback
    }
    throw IllegalStateException("mcmeta: unable to determine latest stable Minecraft version")
  }

  private fun fetchData(version: String): String {
    val loaderUrl = "${extension.rawBase}/${extension.repo}/mc/$version/loader-index.json"
    val artifactsUrl = "${extension.rawBase}/${extension.repo}/mc/$version/artifacts.json"
    val metaUrl = "${extension.rawBase}/${extension.repo}/mc/$version/meta.json"

    val loader = fetchJson(loaderUrl)
    val artifacts = fetchJson(artifactsUrl)
    val meta = fetchJson(metaUrl)

    return gson.toJson(
      McmetaBundle(
        loaderIndex = loader,
        artifacts = artifacts,
        meta = meta,
      )
    )
  }

  private fun fetchLoomIndex(): LoomIndex? {
    val branch = extension.loomBranch.trim()
    if (branch.isEmpty()) {
      return null
    }
    val cacheFile = File(cacheDir, "loom-index.json")
    var payload: String? = if (cacheFile.exists()) cacheFile.readText() else null
    val expired = cacheFile.exists() && isExpired(cacheFile)
    if (payload == null || expired) {
      try {
        val url = "${extension.rawBase}/${extension.repo}/${branch}/loom-index.json"
        payload = fetchText(url)
        cacheFile.writeText(payload)
      } catch (err: Exception) {
        if (payload != null) {
          project.logger.warn("mcmeta: loom fetch failed, using cached data: ${err.message}")
        } else {
          project.logger.warn("mcmeta: loom fetch failed: ${err.message}")
          return null
        }
      }
    }
    return try {
      gson.fromJson(payload, LoomIndex::class.java)
    } catch (err: Exception) {
      project.logger.warn("mcmeta: loom parse failed: ${err.message}")
      null
    }
  }

  private fun fetchJson(url: String): Any {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.setRequestProperty("User-Agent", "mcmeta-gradle/0.1")
    connection.inputStream.bufferedReader().use { reader ->
      return gson.fromJson(reader, Any::class.java)
    }
  }

  private fun applyBundle(
    loaderIndex: LoaderIndex,
    artifacts: Artifacts,
    meta: MetaV1,
    version: String,
  ) {
    val loaders = loaderIndex.loaders
    val fabricLoader = loaders?.fabric?.loader?.firstOrNull()
    val quiltLoader = loaders?.quilt?.loader?.firstOrNull()
    val forgeLoader = loaders?.forge?.loader?.firstOrNull()
    val neoforgeLoader = loaders?.neoforge?.loader?.firstOrNull()

    val minestomVersion = artifacts.runtimes?.minestom?.versions?.firstOrNull()
    val manifoldVersion = artifacts.artifacts?.manifold?.versions?.firstOrNull()
    val fabricApiVersion = artifacts.artifacts?.fabricApi?.versions?.firstOrNull()?.versionNumber
    val paperVersion = artifacts.artifacts?.paper?.versions?.firstOrNull()
    val velocityGroups = artifacts.artifacts?.proxies?.velocity?.groups ?: emptyList()
    val bungeecordGroups = artifacts.artifacts?.proxies?.bungeecord?.groups ?: emptyList()
    val velocityVersions = if (velocityGroups.isNotEmpty()) {
      velocityGroups.flatMap { it.versions ?: emptyList() }
    } else {
      artifacts.artifacts?.velocity?.versions ?: emptyList()
    }
    val velocityVersion = velocityVersions.firstOrNull()
    val bungeecordVersions = bungeecordGroups.flatMap { it.versions ?: emptyList() }
    val bungeecordVersion = bungeecordVersions.firstOrNull()
    val foliaVersion = artifacts.artifacts?.folia?.versions?.firstOrNull()
    val purpurVersion = artifacts.artifacts?.purpur?.versions?.firstOrNull()
    val yarnLatest = meta.yarn?.latest
    val yarnVersions = meta.yarn?.versions ?: emptyList()

    val extra = project.extensions.extraProperties
    extra.set("mcmetaMinecraftVersion", version)
    extra.set("mcmetaFabricLoaderVersion", fabricLoader)
    extra.set("mcmetaQuiltLoaderVersion", quiltLoader)
    extra.set("mcmetaForgeVersion", forgeLoader)
    extra.set("mcmetaNeoForgeVersion", neoforgeLoader)
    extra.set("mcmetaMinestomVersion", minestomVersion)
    extra.set("mcmetaManifoldVersion", manifoldVersion)
    extra.set("mcmetaFabricApiVersion", fabricApiVersion)
    extra.set("mcmetaPaperVersion", paperVersion)
    extra.set("mcmetaVelocityVersion", velocityVersion)
    extra.set("mcmetaFoliaVersion", foliaVersion)
    extra.set("mcmetaPurpurVersion", purpurVersion)
    extra.set("mcmetaJdkVersion", meta.jdk)
    extra.set("mcmetaYarnVersion", yarnLatest)
    extra.set("mcmetaYarnVersions", yarnVersions)

    extra.set("mcmetaFabricLoaderVersions", loaders?.fabric?.loader ?: emptyList<String>())
    extra.set("mcmetaQuiltLoaderVersions", loaders?.quilt?.loader ?: emptyList<String>())
    extra.set("mcmetaForgeVersions", loaders?.forge?.loader ?: emptyList<String>())
    extra.set("mcmetaNeoForgeVersions", loaders?.neoforge?.loader ?: emptyList<String>())
    extra.set("mcmetaMinestomVersions", artifacts.runtimes?.minestom?.versions ?: emptyList<String>())
    extra.set("mcmetaManifoldVersions", artifacts.artifacts?.manifold?.versions ?: emptyList<String>())
    extra.set(
      "mcmetaFabricApiVersions",
      artifacts.artifacts?.fabricApi?.versions?.map { it.versionNumber } ?: emptyList<String>()
    )
    extra.set("mcmetaPaperVersions", artifacts.artifacts?.paper?.versions ?: emptyList<String>())
    extra.set("mcmetaVelocityVersions", velocityVersions)
    extra.set("mcmetaVelocityApiVersions", velocityGroups.mapNotNull { it.api })
    extra.set(
      "mcmetaVelocityVersionGroups",
      velocityGroups.associate { group -> group.api to (group.versions ?: emptyList()) }
    )
    extra.set(
      "mcmetaVelocityVersionRanges",
      velocityGroups.mapNotNull { group ->
        val range = group.range ?: return@mapNotNull null
        group.api to range
      }.toMap()
    )
    extra.set("mcmetaBungeeCordVersion", bungeecordVersion)
    extra.set("mcmetaBungeeCordVersions", bungeecordVersions)
    extra.set("mcmetaBungeeCordApiVersions", bungeecordGroups.mapNotNull { it.api })
    extra.set(
      "mcmetaBungeeCordVersionGroups",
      bungeecordGroups.associate { group -> group.api to (group.versions ?: emptyList()) }
    )
    extra.set(
      "mcmetaBungeeCordVersionRanges",
      bungeecordGroups.mapNotNull { group ->
        val range = group.range ?: return@mapNotNull null
        group.api to range
      }.toMap()
    )
    extra.set("mcmetaFoliaVersions", artifacts.artifacts?.folia?.versions ?: emptyList<String>())
    extra.set("mcmetaPurpurVersions", artifacts.artifacts?.purpur?.versions ?: emptyList<String>())
  }

  private fun applyLoom(loom: LoomIndex?) {
    if (loom == null) return
    val extra = project.extensions.extraProperties

    val fabric = loom.fabric
    if (!fabric?.latest.isNullOrBlank()) {
      extra.set("mcmetaFabricLoomVersion", fabric?.latest)
    }
    if (!fabric?.stable.isNullOrBlank()) {
      extra.set("mcmetaFabricLoomStableVersion", fabric?.stable)
    }
    if (!fabric?.snapshot.isNullOrBlank()) {
      extra.set("mcmetaFabricLoomSnapshotVersion", fabric?.snapshot)
    }
    if (!fabric?.versions.isNullOrEmpty()) {
      extra.set("mcmetaFabricLoomVersions", fabric?.versions ?: emptyList<String>())
    }
    if (!fabric?.latest.isNullOrBlank()) {
      val isSnapshot = fabric?.latest?.contains("SNAPSHOT", ignoreCase = true) == true
      extra.set("mcmetaFabricLoomIsSnapshot", isSnapshot)
    }

    val quilt = loom.quilt
    if (!quilt?.latest.isNullOrBlank()) {
      extra.set("mcmetaQuiltLoomVersion", quilt?.latest)
    }
    if (!quilt?.stable.isNullOrBlank()) {
      extra.set("mcmetaQuiltLoomStableVersion", quilt?.stable)
    }
    if (!quilt?.snapshot.isNullOrBlank()) {
      extra.set("mcmetaQuiltLoomSnapshotVersion", quilt?.snapshot)
    }
    if (!quilt?.versions.isNullOrEmpty()) {
      extra.set("mcmetaQuiltLoomVersions", quilt?.versions ?: emptyList<String>())
    }
    if (!quilt?.latest.isNullOrBlank()) {
      val isSnapshot = quilt?.latest?.contains("SNAPSHOT", ignoreCase = true) == true
      extra.set("mcmetaQuiltLoomIsSnapshot", isSnapshot)
    }
  }

  private fun configureManifoldPreprocessor(artifacts: Artifacts, requestedVersion: String) {
    val manifoldVersion = extension.manifoldPreprocessorVersion?.takeIf { it.isNotBlank() }
      ?: artifacts.artifacts?.manifold?.versions?.firstOrNull()
    if (manifoldVersion.isNullOrBlank()) {
      project.logger.warn("mcmeta: manifold preprocessor enabled but no manifold version found")
      return
    }

    val manifest = loadManifest() ?: run {
      project.logger.warn("mcmeta: failed to load Mojang manifest for preprocessor symbols")
      return
    }

    val symbols = buildPreprocessorSymbols(manifest, requestedVersion)
    if (symbols.isEmpty()) {
      project.logger.warn("mcmeta: no preprocessor symbols generated for $requestedVersion")
      return
    }
    if (!symbols.containsKey("MC_VER")) {
      project.logger.warn("mcmeta: requested version not found in manifest, MC_VER not set")
    }

    project.dependencies.add(
      "annotationProcessor",
      "systems.manifold:manifold-preprocessor:$manifoldVersion"
    )
    project.dependencies.add(
      "testAnnotationProcessor",
      "systems.manifold:manifold-preprocessor:$manifoldVersion"
    )

    val hasModuleInfo = hasModuleInfo()

    project.tasks.withType(JavaCompile::class.java).configureEach { task ->
      val args = buildCompilerArgs(symbols, hasModuleInfo, task.classpath.asPath)
      val compilerArgs = task.options.compilerArgs
      args.forEach { arg ->
        if (!compilerArgs.contains(arg)) {
          compilerArgs.add(arg)
        }
      }
    }
  }

  private fun loadManifest(): MojangManifest? {
    return try {
      val cacheFile = File(cacheDir, "manifest.json")
      var payload: String? = if (cacheFile.exists()) cacheFile.readText() else null
      val expired = cacheFile.exists() && isExpired(cacheFile)
      if (payload == null || expired) {
        try {
          val text = fetchText(extension.manifestUrl)
          cacheFile.writeText(text)
          payload = text
        } catch (err: Exception) {
          if (payload != null) {
            project.logger.warn("mcmeta: manifest fetch failed, using cached data: ${err.message}")
          } else {
            return null
          }
        }
      }
      gson.fromJson(payload, MojangManifest::class.java)
    } catch (err: Exception) {
      project.logger.warn("mcmeta: manifest fetch failed: ${err.message}")
      null
    }
  }

  private fun buildCompilerArgs(
    symbols: Map<String, Int>,
    hasModuleInfo: Boolean,
    classpath: String,
  ): List<String> {
    val args = ArrayList<String>()
    args.add("-Xplugin:Manifold")
    if (JavaVersion.current() != JavaVersion.VERSION_1_8 && hasModuleInfo) {
      args.add("--module-path")
      args.add(classpath)
    }
    val sorted = symbols.entries.sortedBy { it.key }
    for ((key, value) in sorted) {
      args.add("-A${key}=${value}")
    }
    return args
  }

  private fun buildPreprocessorSymbols(
    manifest: MojangManifest,
    requestedVersion: String,
  ): Map<String, Int> {
    val versions = manifest.versions ?: return emptyMap()
    val total = versions.size
    val out = LinkedHashMap<String, Int>()
    val requestedSanitized = sanitizeVersion(requestedVersion)
    var requestedRank: Int? = null

    versions.forEachIndexed { index, version ->
      val rank = total - index
      val constName = "MC_${toConstName(version.id)}"
      if (!out.containsKey(constName)) {
        out[constName] = rank
      }
      if (version.id == requestedVersion || sanitizeVersion(version.id) == requestedSanitized) {
        requestedRank = rank
      }
    }

    if (requestedRank != null) {
      out["MC_VER"] = requestedRank!!
    }

    return out
  }

  private fun toConstName(value: String): String {
    val out = StringBuilder()
    var prevUnderscore = false
    for (ch in value) {
      val normalized = when {
        ch.isLetterOrDigit() -> ch.uppercaseChar()
        else -> '_'
      }
      if (normalized == '_') {
        if (prevUnderscore) continue
        prevUnderscore = true
      } else {
        prevUnderscore = false
      }
      out.append(normalized)
    }
    return out.toString().trim('_')
  }

  private fun hasModuleInfo(): Boolean {
    val sourceSets = project.extensions.findByType(SourceSetContainer::class.java) ?: return false
    return sourceSets.any { sourceSet ->
      sourceSet.allJava.files.any { it.name == "module-info.java" }
    }
  }

  private fun fetchText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.setRequestProperty("User-Agent", "mcmeta-gradle/0.1")
    connection.inputStream.bufferedReader().use { reader ->
      return reader.readText()
    }
  }

  private fun isExpired(file: File): Boolean {
    val ttl = Duration.ofMinutes(extension.cacheMinutes)
    val age = System.currentTimeMillis() - file.lastModified()
    return age > ttl.toMillis()
  }
}

private fun sanitizeVersion(value: String): String {
  val out = StringBuilder()
  var prevDash = false

  for (ch in value) {
    val normalized = when {
      ch.isLetterOrDigit() -> ch.lowercaseChar()
      ch == '.' -> '.'
      ch == '-' || ch == '_' -> '-'
      ch.isWhitespace() -> '-'
      else -> null
    }

    if (normalized == null) continue

    if (normalized == '-') {
      if (prevDash) continue
      prevDash = true
      out.append('-')
    } else {
      prevDash = false
      out.append(normalized)
    }
  }

  return out.toString().trim('.').trim('-')
}

private data class McmetaBundle(
  val loaderIndex: Any,
  val artifacts: Any,
  val meta: Any,
)

private data class MojangManifest(
  val versions: List<MojangVersion>?,
  val latest: MojangLatest?,
)

private data class MetaV1(
  val schema: String?,
  val minecraft: String?,
  val sources: Map<String, String>?,
  val notes: List<String>?,
  val jdk: Int?,
  val yarn: YarnMeta?,
)

private data class MojangVersion(
  val id: String,
)

private data class MojangLatest(
  val release: String?,
  val snapshot: String?,
)

private data class LoaderIndex(
  val loaders: LoaderFamilies?,
)

private data class LoaderFamilies(
  val fabric: LoaderFamily?,
  val quilt: LoaderFamily?,
  val forge: LoaderFamily?,
  val neoforge: LoaderFamily?,
)

private data class LoaderFamily(
  val loader: List<String>?,
)

private data class Artifacts(
  val artifacts: ArtifactFamilies?,
  val runtimes: RuntimeFamilies?,
)

private data class ArtifactFamilies(
  @SerializedName("fabric-api")
  val fabricApi: ModrinthArtifact?,
  val manifold: MavenArtifact?,
  val paper: ProjectArtifact?,
  val velocity: ProjectArtifact?,
  val folia: ProjectArtifact?,
  val purpur: ProjectArtifact?,
  val proxies: Proxies?,
)

private data class RuntimeFamilies(
  val minestom: MavenArtifact?,
)

private data class MavenArtifact(
  val versions: List<String>?,
)

private data class YarnMeta(
  val latest: String?,
  val versions: List<String>?,
)

private data class ModrinthArtifact(
  val versions: List<ModrinthVersion>?,
)

private data class ProjectArtifact(
  val versions: List<String>?,
)

private data class Proxies(
  val velocity: ProxyArtifact?,
  val bungeecord: ProxyArtifact?,
)

private data class ProxyArtifact(
  val groups: List<ProxyGroup>?,
)

private data class ProxyGroup(
  val api: String,
  val versions: List<String>?,
  val range: ProxyRange?,
)

private data class ProxyRange(
  val newest: String?,
  val oldest: String?,
)

private data class ModrinthVersion(
  @SerializedName("version_number")
  val versionNumber: String,
)

private data class LoomIndex(
  val fabric: LoomEntry?,
  val quilt: LoomEntry?,
)

private data class LoomEntry(
  val latest: String?,
  val stable: String?,
  val snapshot: String?,
  val versions: List<String>?,
)
