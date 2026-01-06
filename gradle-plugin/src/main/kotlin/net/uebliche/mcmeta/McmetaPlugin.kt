package net.uebliche.mcmeta

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.gradle.api.Plugin
import org.gradle.api.Project
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
      group = "mcmeta"
      description = "Loads mcmeta versions into project properties."
      doLast {
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

  fun resolve() {
    val sanitized = sanitizeVersion(extension.minecraftVersion)
    if (sanitized.isBlank()) {
      throw IllegalStateException("mcmeta: invalid minecraftVersion")
    }

    val cacheDir = File(project.gradle.gradleUserHomeDir, "caches/mcmeta")
    cacheDir.mkdirs()
    val cacheFile = File(cacheDir, "$sanitized.json")

    val payload = if (cacheFile.exists() && !isExpired(cacheFile)) {
      cacheFile.readText()
    } else {
      val data = fetchData(sanitized)
      cacheFile.writeText(data)
      data
    }

    val bundle = gson.fromJson(payload, McmetaBundle::class.java)
    applyBundle(bundle, sanitized)
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

  private fun applyBundle(bundle: McmetaBundle, version: String) {
    val loaderIndex = gson.fromJson(gson.toJson(bundle.loaderIndex), LoaderIndex::class.java)
    val artifacts = gson.fromJson(gson.toJson(bundle.artifacts), Artifacts::class.java)

    val loaders = loaderIndex.loaders
    val fabricLoader = loaders?.fabric?.loader?.firstOrNull()
    val quiltLoader = loaders?.quilt?.loader?.firstOrNull()
    val forgeLoader = loaders?.forge?.loader?.firstOrNull()
    val neoforgeLoader = loaders?.neoforge?.loader?.firstOrNull()

    val minestomVersion = artifacts.runtimes?.minestom?.versions?.firstOrNull()
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
    extra.set("mcmetaFabricApiVersion", fabricApiVersion)
    extra.set("mcmetaPaperVersion", paperVersion)
    extra.set("mcmetaVelocityVersion", velocityVersion)
    extra.set("mcmetaFoliaVersion", foliaVersion)

    extra.set("mcmetaFabricLoaderVersions", loaders?.fabric?.loader ?: emptyList<String>())
    extra.set("mcmetaQuiltLoaderVersions", loaders?.quilt?.loader ?: emptyList<String>())
    extra.set("mcmetaForgeVersions", loaders?.forge?.loader ?: emptyList<String>())
    extra.set("mcmetaNeoForgeVersions", loaders?.neoforge?.loader ?: emptyList<String>())
    extra.set("mcmetaMinestomVersions", artifacts.runtimes?.minestom?.versions ?: emptyList<String>())
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
