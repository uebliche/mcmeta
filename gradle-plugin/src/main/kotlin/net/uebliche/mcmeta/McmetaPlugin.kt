package net.uebliche.mcmeta

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
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
  var cacheMinutes: Long = 30
  var autoLoad: Boolean = true
  var enableManifoldPreprocessor: Boolean = false
  var manifoldPreprocessorVersion: String? = null

  fun resolveNow(project: Project) {
    McmetaResolver(project, this).resolve()
  }
}

class McmetaPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("mcmeta", McmetaExtension::class.java)

    project.afterEvaluate {
      if (!extension.autoLoad) return@afterEvaluate
      if (extension.minecraftVersion.isBlank()) {
        project.logger.warn("mcmeta: minecraftVersion is empty")
        return@afterEvaluate
      }

      val resolver = McmetaResolver(project, extension)
      resolver.resolve()
    }

    project.tasks.register("mcmetaResolve") {
      it.group = "mcmeta"
      it.description = "Loads mcmeta versions into project properties."
      it.doLast {
        if (extension.minecraftVersion.isBlank()) {
          throw IllegalStateException("mcmeta.minecraftVersion must be set")
        }
        McmetaResolver(project, extension).resolve()
      }
    }
  }
}

private class McmetaResolver(
  private val project: Project,
  private val extension: McmetaExtension,
) {
  private val gson = Gson()
  private val cacheDir = File(project.gradle.gradleUserHomeDir, "caches/mcmeta").apply { mkdirs() }

  fun resolve() {
    val sanitized = sanitizeVersion(extension.minecraftVersion)
    if (sanitized.isBlank()) {
      throw IllegalStateException("mcmeta: invalid minecraftVersion")
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
    applyBundle(loaderIndex, artifacts, sanitized)
    if (extension.enableManifoldPreprocessor) {
      configureManifoldPreprocessor(artifacts, extension.minecraftVersion)
    }
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

  private fun fetchJson(url: String): Any {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.setRequestProperty("User-Agent", "mcmeta-gradle/0.1")
    connection.inputStream.bufferedReader().use { reader ->
      return gson.fromJson(reader, Any::class.java)
    }
  }

  private fun applyBundle(loaderIndex: LoaderIndex, artifacts: Artifacts, version: String) {
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
)

private data class MojangVersion(
  val id: String,
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
  val proxies: Proxies?,
)

private data class RuntimeFamilies(
  val minestom: MavenArtifact?,
)

private data class MavenArtifact(
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
