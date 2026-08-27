package net.uebliche.mcmeta

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import groovy.lang.Closure
import org.codehaus.groovy.runtime.InvokerHelper
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.sun.net.httpserver.HttpServer
import javax.xml.parsers.DocumentBuilderFactory

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

private data class FabricBuildInfo(
  val minecraftVersion: String,
  val loaderVersion: String?,
  val fabricApiVersion: String?,
  val buildable: Boolean,
  val blockedBy: String?,
  val mappingChannel: String?,
  val mappingVersion: String?,
  val availableMappingChannels: List<String>,
)

private class McmetaFabricSupport(private val owner: Project) {
  fun resolve(project: Project, requestedMinecraftVersion: String): FabricBuildInfo {
    val extra = preferredExtraProperties(owner, "mcmetaMinecraftVersion")
    val resolvedVersion = extraStringOrNull(extra, "mcmetaMinecraftVersion").orEmpty()
    if (requestedMinecraftVersion.isNotBlank()
      && resolvedVersion.isNotBlank()
      && requestedMinecraftVersion != resolvedVersion
    ) {
      throw GradleException(
        "mcmeta Fabric runtime resolved for $resolvedVersion, but build requested $requestedMinecraftVersion"
      )
    }

    val loaderVersion = extraStringOrNull(extra, "mcmetaFabricLoaderVersion")
    val fabricApiVersion = extraStringOrNull(extra, "mcmetaFabricApiVersion")
    val mappingChannel = extraStringOrNull(extra, "mcmetaFabricMappingChannel")
    val mappingVersion = extraStringOrNull(extra, "mcmetaFabricMappingVersion")
    val blockedBy = extraStringOrNull(extra, "mcmetaFabricBlockedBy")
    val availableChannels = extraList(extra, "mcmetaFabricAvailableMappingChannels")
    val buildable = extraBoolean(extra, "mcmetaFabricBuildable")

    return FabricBuildInfo(
      minecraftVersion = if (resolvedVersion.isNotBlank()) resolvedVersion else requestedMinecraftVersion,
      loaderVersion = loaderVersion,
      fabricApiVersion = fabricApiVersion,
      buildable = buildable,
      blockedBy = blockedBy,
      mappingChannel = mappingChannel,
      mappingVersion = mappingVersion,
      availableMappingChannels = availableChannels,
    )
  }

  fun applyMappings(project: Project, dependencyHandler: DependencyHandler) {
    val info = resolve(project, resolveRequestedMinecraftVersion(project))
    if (!info.buildable) {
      val detail = info.blockedBy ?: "unknown"
      throw GradleException(
        "mcmeta: Fabric build blocked for ${info.minecraftVersion}: ${detail}"
      )
    }

    when (info.mappingChannel) {
      "mojang" -> {
        val loom = project.extensions.findByName("loom")
          ?: throw GradleException("mcmeta: Fabric Loom extension missing for official Mojang mappings")
        val mappings = InvokerHelper.invokeMethod(loom, "officialMojangMappings", emptyArray<Any>())
        dependencyHandler.add("mappings", mappings)
      }
      "yarn" -> {
        val version = info.mappingVersion
          ?: throw GradleException("mcmeta: Yarn mapping version missing for ${info.minecraftVersion}")
        dependencyHandler.add("mappings", "net.fabricmc:yarn:${version}:v2")
      }
      "intermediary" -> {
        val version = info.mappingVersion
          ?: throw GradleException("mcmeta: Intermediary mapping version missing for ${info.minecraftVersion}")
        dependencyHandler.add("mappings", "net.fabricmc:intermediary:${version}:v2")
      }
      "unobfuscated" -> applyUnobfuscatedMappings(project, dependencyHandler, info.minecraftVersion)
      else -> throw GradleException(
        "mcmeta: unsupported Fabric mapping channel '${info.mappingChannel ?: "missing"}' for ${info.minecraftVersion}"
      )
    }
  }
}

private fun applyUnobfuscatedMappings(
  project: Project,
  dependencyHandler: DependencyHandler,
  minecraftVersion: String,
) {
  project.extensions.extraProperties.set("fabric.loom.disableObfuscation", "true")
  val loom = project.extensions.findByName("loom")
    ?: throw GradleException("mcmeta: Fabric Loom extension missing for unobfuscated mappings")
  val generatedDir = File(project.layout.buildDirectory.get().asFile, "generated/mcmeta")
  generatedDir.mkdirs()

  val metadataFile = File(generatedDir, "minecraft-$minecraftVersion-identity-mappings.json")
  if (!metadataFile.isFile) {
    val targetMetadata = loadMojangVersionMetadata(minecraftVersion)
    val mappingMetadata = loadMojangVersionMetadata("1.21.11")
    val targetDownloads = targetMetadata.getAsJsonObject("downloads")
      ?: throw GradleException("Minecraft $minecraftVersion has no download metadata.")
    val mappingDownloads = mappingMetadata.getAsJsonObject("downloads")
      ?: throw GradleException("Minecraft 1.21.11 has no download metadata.")
    for (key in listOf("client_mappings", "server_mappings")) {
      val mapping = mappingDownloads.get(key)
        ?: throw GradleException("Minecraft 1.21.11 has no $key download.")
      targetDownloads.add(key, mapping.deepCopy())
    }
    metadataFile.writeText(Gson().toJson(targetMetadata))
  }

  val mappingsFile = File(generatedDir, "unobfuscated-$minecraftVersion-mappings.jar")
  writeTinyMappingsJar(mappingsFile, "tiny\t2\t0\tofficial\tnamed\n")
  val intermediaryFile = File(generatedDir, "unobfuscated-$minecraftVersion-intermediary.jar")
  writeTinyMappingsJar(intermediaryFile, "tiny\t2\t0\tofficial\tintermediary\n")

  val metadataUrl = serveBuildFile(project, metadataFile, "application/json")
  val intermediaryUrl = serveBuildFile(project, intermediaryFile, "application/java-archive")
  setGradleProperty(loom, "customMinecraftMetadata", metadataUrl)
  setGradleProperty(loom, "intermediaryUrl", intermediaryUrl)
  setGradleProperty(loom, "productionNamespace", "official")
  setGradleProperty(loom, "useIntermediateMappings", true)
  if (project.configurations.findByName("mappings") != null) {
    dependencyHandler.add("mappings", project.files(mappingsFile))
  }
  project.logger.lifecycle("Using Minecraft $minecraftVersion with identity Fabric mappings.")
}

private fun writeTinyMappingsJar(file: File, content: String) {
  if (file.isFile) {
    return
  }
  FileOutputStream(file).use { output ->
    ZipOutputStream(output).use { zip ->
      zip.putNextEntry(ZipEntry("mappings/mappings.tiny"))
      zip.write(content.toByteArray(Charsets.UTF_8))
      zip.closeEntry()
    }
  }
}

private fun loadMojangVersionMetadata(version: String): JsonObject {
  val gson = Gson()
  val manifest = URL(DEFAULT_MANIFEST).openStream().bufferedReader().use {
    gson.fromJson(it, JsonObject::class.java)
  }
  val entry = manifest.getAsJsonArray("versions")
    .mapNotNull { it.asJsonObject }
    .firstOrNull { it.get("id")?.asString == version }
    ?: throw GradleException("Minecraft $version is not present in the Mojang release manifest.")
  val metadataUrl = entry.get("url")?.asString
    ?: throw GradleException("Minecraft $version has no Mojang metadata URL.")
  return URL(metadataUrl).openStream().bufferedReader().use {
    gson.fromJson(it, JsonObject::class.java)
  }
}

private fun serveBuildFile(project: Project, file: File, contentType: String): String {
  val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  val path = "/${file.name}"
  server.createContext(path) { exchange ->
    val bytes = file.readBytes()
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }
  server.start()
  project.gradle.buildFinished { server.stop(0) }
  return "http://127.0.0.1:${server.address.port}$path"
}

private fun setGradleProperty(owner: Any, name: String, value: Any) {
  val property = InvokerHelper.getProperty(owner, name)
  InvokerHelper.invokeMethod(property, "set", arrayOf(value))
}

private fun applyOptions(project: Project, extension: McmetaExtension) {
  configureRepositories(project, extension.repositories)
  configureDependencies(project, extension)
  configureNeoForgeRuns(project, extension)
  project.afterEvaluate {
    configureLoomRuns(project)
  }
  if (project.subprojects.isNotEmpty()) {
    project.subprojects.forEach { subproject ->
      subproject.afterEvaluate {
        configureLoomRuns(subproject)
      }
    }
  }
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

private fun configureNeoForgeRuns(project: Project, extension: McmetaExtension) {
  if (!shouldConfigureNeoForge(project)) return
  if (!project.plugins.hasPlugin("net.neoforged.moddev")) return
  if (!ensureResolved(project, extension)) return
  if (project.tasks.findByName("runClient") != null) return

  val requestedMcVersion = resolveRequestedMinecraftVersion(project)
  val neoForgeVersion = resolveNeoForgeVersion(project, requestedMcVersion)
  configureNeoForgeRuns(project, requestedMcVersion, neoForgeVersion)
}

private fun shouldConfigureNeoForge(project: Project): Boolean {
  val name = project.name.lowercase()
  return name == "loader-neoforge"
}

private fun resolveRequestedMinecraftVersion(project: Project): String {
  val propVersion = project.findProperty("mcVersion")?.toString()?.trim()
  if (!propVersion.isNullOrEmpty()) return propVersion
  val mcmeta = extraStringFromProjectOrRoot(project, "mcmetaMinecraftVersion")
  if (!mcmeta.isNullOrEmpty()) return mcmeta
  val fallback = project.findProperty("minecraft_version")?.toString()?.trim()
  return fallback ?: ""
}

private fun resolveNeoForgeVersion(project: Project, mcVersion: String): String {
  val override = project.findProperty("neoForgeVersion")?.toString()?.trim()
  if (!override.isNullOrEmpty()) return override

  val mcmetaMc = extraStringFromProjectOrRoot(project, "mcmetaMinecraftVersion")
  val mcmetaVersion = extraStringFromProjectOrRoot(project, "mcmetaNeoForgeVersion")
  if (!mcmetaMc.isNullOrEmpty() && mcmetaMc == mcVersion && !mcmetaVersion.isNullOrEmpty()) {
    return mcmetaVersion
  }

  val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
  val versions = parseMavenVersions(xml)
  val prefix = mcVersion.split('.').drop(1).let { parts ->
    if (parts.size == 1) listOf(parts[0], "0") else parts
  }.joinToString(".")
  val matching = versions.filter { it.startsWith(prefix) }
  if (matching.isEmpty()) {
    throw IllegalStateException("No NeoForge version matching prefix $prefix (derived from Minecraft $mcVersion)")
  }
  return matching.maxWithOrNull { a, b ->
    compareVersionStrings(a, b)
  } ?: matching.last()
}

private fun configureNeoForgeRuns(project: Project, mcVersion: String, neoForgeVersion: String) {
  val neoForge = project.extensions.findByName("neoForge") ?: return
  InvokerHelper.setProperty(neoForge, "version", neoForgeVersion)

  val runRoot = project.layout.projectDirectory.dir("run")
  val runClientDir = runRoot.dir("client-$mcVersion").asFile
  val runServerDir = runRoot.dir("server-$mcVersion").asFile
  val runDataDir = runRoot.dir("data-$mcVersion").asFile

  val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
  val mainSourceSet = sourceSets?.findByName("main")
  val commonProject = project.rootProject.findProject("common")
  val commonSourceSets = commonProject?.extensions?.findByType(SourceSetContainer::class.java)
  val commonMain = commonSourceSets?.findByName("main")

  val runClosure = groovyClosure(neoForge) { runs ->
    InvokerHelper.invokeMethod(
      runs,
      "configureEach",
      arrayOf(groovyClosure(runs) { run ->
        InvokerHelper.invokeMethod(run, "systemProperty", arrayOf("mod.mcVersion", mcVersion))
      })
    )
    val programArgs = resolveMinecraftProgramArgs(project)
    configureRun(runs, "client") { client ->
      InvokerHelper.invokeMethod(client, "client", arrayOf<Any>())
      InvokerHelper.setProperty(client, "logLevel", org.slf4j.event.Level.INFO)
      InvokerHelper.setProperty(client, "gameDirectory", runClientDir)
      applyProgramArguments(client, programArgs)
    }
    configureRun(runs, "server") { server ->
      InvokerHelper.invokeMethod(server, "server", arrayOf<Any>())
      InvokerHelper.setProperty(server, "gameDirectory", runServerDir)
    }
    configureRun(runs, "data") { data ->
      InvokerHelper.invokeMethod(data, "data", arrayOf<Any>())
      InvokerHelper.setProperty(data, "gameDirectory", runDataDir)
    }
  }
  InvokerHelper.invokeMethod(neoForge, "runs", arrayOf(runClosure))

  val modId = project.findProperty("mod_id")?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
    ?: project.name
  val modsClosure = groovyClosure(neoForge) { mods ->
    InvokerHelper.invokeMethod(
      mods,
      "create",
      arrayOf(modId, groovyClosure(mods) { mod ->
        if (mainSourceSet != null) {
          InvokerHelper.invokeMethod(mod, "sourceSet", arrayOf(mainSourceSet))
        }
        if (commonMain != null) {
          InvokerHelper.invokeMethod(mod, "sourceSet", arrayOf(commonMain))
        }
      })
    )
  }
  InvokerHelper.invokeMethod(neoForge, "mods", arrayOf(modsClosure))
}

private fun configureLoomRuns(project: Project) {
  val loom = project.extensions.findByName("loom") ?: return
  val programArgs = resolveMinecraftProgramArgs(project)
  if (programArgs.isEmpty()) return
  val runs = InvokerHelper.getProperty(loom, "runs") ?: return
  InvokerHelper.invokeMethod(
    runs,
    "configureEach",
    arrayOf(groovyClosure(runs) { run ->
      val name = InvokerHelper.getProperty(run, "name")?.toString()?.lowercase()
      if (name == "client") {
        applyLoomProgramArguments(run, programArgs)
      }
    })
  )
  configureLoomRunClientTask(project, programArgs)
}

private fun configureLoomRunClientTask(project: Project, programArgs: List<String>) {
  if (programArgs.isEmpty()) return
  project.tasks.matching { it.name == "runClient" }.configureEach { task ->
    try {
      if (task is org.gradle.api.tasks.JavaExec) {
        task.args(programArgs)
        return@configureEach
      }
    } catch (err: Exception) {
    }
    try {
      InvokerHelper.invokeMethod(task, "args", arrayOf(programArgs))
    } catch (err: Exception) {
    }
  }
}

private fun resolveMinecraftProgramArgs(project: Project): List<String> {
  val rawMinecraft = project.findProperty("minecraftArgs")?.toString()?.trim()
  val rawMcArgs = project.findProperty("mcArgs")?.toString()?.trim()
  val raw = rawMinecraft ?: rawMcArgs
  if (raw.isNullOrEmpty()) return emptyList()
  return parseCommandLineArgs(raw)
}

private fun applyProgramArguments(run: Any, args: List<String>) {
  if (args.isEmpty()) return
  for (arg in args) {
    InvokerHelper.invokeMethod(run, "programArgument", arrayOf(arg))
  }
}

private fun applyLoomProgramArguments(run: Any, args: List<String>) {
  if (args.isEmpty()) return
  try {
    InvokerHelper.invokeMethod(run, "programArgs", arrayOf(args))
    return
  } catch (err: Exception) {
  }
  try {
    InvokerHelper.invokeMethod(run, "programArgs", arrayOf(args.toTypedArray()))
    return
  } catch (err: Exception) {
  }
  try {
    for (arg in args) {
      InvokerHelper.invokeMethod(run, "programArgument", arrayOf(arg))
    }
    return
  } catch (err: Exception) {
  }
  for (arg in args) {
    try {
      InvokerHelper.invokeMethod(run, "programArg", arrayOf(arg))
    } catch (err: Exception) {
    }
  }
}

private fun parseCommandLineArgs(input: String): List<String> {
  val out = ArrayList<String>()
  val current = StringBuilder()
  var inSingle = false
  var inDouble = false
  var i = 0
  while (i < input.length) {
    val ch = input[i]
    when (ch) {
      '\\' -> {
        if (i + 1 < input.length) {
          current.append(input[i + 1])
          i += 2
          continue
        }
        current.append(ch)
      }
      '"' -> {
        if (!inSingle) {
          inDouble = !inDouble
        } else {
          current.append(ch)
        }
      }
      '\'' -> {
        if (!inDouble) {
          inSingle = !inSingle
        } else {
          current.append(ch)
        }
      }
      ' ', '\t', '\n', '\r' -> {
        if (inSingle || inDouble) {
          current.append(ch)
        } else if (current.isNotEmpty()) {
          out.add(current.toString())
          current.setLength(0)
        }
      }
      else -> current.append(ch)
    }
    i += 1
  }
  if (current.isNotEmpty()) {
    out.add(current.toString())
  }
  return out
}

private fun configureRun(container: Any, name: String, block: (Any) -> Unit) {
  val existing = try {
    InvokerHelper.invokeMethod(container, "findByName", arrayOf(name))
  } catch (_: Exception) {
    null
  }
  if (existing != null) {
    block(existing)
    return
  }
  InvokerHelper.invokeMethod(
    container,
    "create",
    arrayOf(name, groovyClosure(container) { run -> block(run) })
  )
}

private fun groovyClosure(owner: Any, block: (Any) -> Unit): Closure<Any?> {
  return object : Closure<Any?>(owner) {
    @Suppress("unused")
    fun doCall(arg: Any) {
      block(arg)
    }
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

private fun parseMavenMetadata(xml: String): String? {
  val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    .parse(xml.byteInputStream())
  val release = doc.getElementsByTagName("release").item(0)?.textContent?.trim()
  if (!release.isNullOrEmpty()) return release
  val latest = doc.getElementsByTagName("latest").item(0)?.textContent?.trim()
  if (!latest.isNullOrEmpty()) return latest
  val versions = parseMavenVersions(doc)
  return versions.lastOrNull()
}

private fun parseMavenVersions(xml: String): List<String> {
  val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    .parse(xml.byteInputStream())
  return parseMavenVersions(doc)
}

private fun parseMavenVersions(doc: org.w3c.dom.Document): List<String> {
  val nodes = doc.getElementsByTagName("version")
  val out = ArrayList<String>(nodes.length)
  for (i in 0 until nodes.length) {
    val value = nodes.item(i)?.textContent?.trim()
    if (!value.isNullOrEmpty()) {
      out.add(value)
    }
  }
  return out
}

private fun compareVersionStrings(a: String, b: String): Int {
  val aParts = a.split(Regex("[^0-9]+"))
    .filter { it.isNotEmpty() }
    .map { it.toIntOrNull() ?: 0 }
  val bParts = b.split(Regex("[^0-9]+"))
    .filter { it.isNotEmpty() }
    .map { it.toIntOrNull() ?: 0 }
  val max = maxOf(aParts.size, bParts.size)
  for (i in 0 until max) {
    val av = aParts.getOrElse(i) { 0 }
    val bv = bParts.getOrElse(i) { 0 }
    if (av != bv) return av.compareTo(bv)
  }
  return a.compareTo(b, ignoreCase = true)
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
    var resolvedVersion = resolveRequestedVersion()
    var sanitized = sanitizeVersion(resolvedVersion)
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
          val localBundle = loadLocalBundle(sanitized)
          if (localBundle != null) {
            cacheFile.writeText(localBundle.payload)
            payload = localBundle.payload
            if (!localBundle.exactMatch) {
              project.logger.warn(
                "mcmeta: fetch failed, using local backup ${localBundle.version} for requested $sanitized"
              )
              resolvedVersion = localBundle.version
              sanitized = localBundle.version
              extension.minecraftVersion = resolvedVersion
            } else {
              project.logger.warn("mcmeta: fetch failed, using local mcmeta backup for $sanitized")
            }
          } else {
            throw IllegalStateException("mcmeta: unable to fetch data and no cache available", err)
          }
        }
      }
    }

    val bundle = gson.fromJson(payload, McmetaBundle::class.java)
    val loaderIndex = gson.fromJson(gson.toJson(bundle.loaderIndex), LoaderIndex::class.java)
    val artifacts = gson.fromJson(gson.toJson(bundle.artifacts), Artifacts::class.java)
    val meta = gson.fromJson(gson.toJson(bundle.meta), MetaV1::class.java)
    val buildability = bundle.buildability?.let {
      gson.fromJson(gson.toJson(it), BuildabilityV1::class.java)
    }
    applyBundle(loaderIndex, artifacts, meta, buildability, sanitized)
    applyProxyFallbacks()
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
    val buildabilityUrl = "${extension.rawBase}/${extension.repo}/mc/$version/buildability.json"

    val loader = fetchJson(loaderUrl)
    val artifacts = fetchJson(artifactsUrl)
    val meta = fetchJson(metaUrl)
    val buildability = fetchJsonOrNull(buildabilityUrl)

    return gson.toJson(
      McmetaBundle(
        loaderIndex = loader,
        artifacts = artifacts,
        meta = meta,
        buildability = buildability,
      )
    )
  }

  private data class LocalBundle(
    val payload: String,
    val version: String,
    val exactMatch: Boolean,
  )

  private fun loadLocalBundle(version: String): LocalBundle? {
    val backupDirs = listBackupDirs()
    val exactMatch = backupDirs.firstNotNullOfOrNull { dir ->
      readLocalBundle(dir, version, exactOnly = true)
    }
    if (exactMatch != null) {
      return exactMatch
    }
    val prefix = majorMinorPrefix(version) ?: return null
    val prefixMatch = backupDirs.firstNotNullOfOrNull { dir ->
      readLocalBundle(dir, prefix, exactOnly = false)
    }
    if (prefixMatch != null) {
      return prefixMatch
    }
    return backupDirs.firstNotNullOfOrNull { dir ->
      readLocalBundle(dir, version, exactOnly = false, ignorePrefix = true)
    }
  }

  private fun readLocalBundle(
    dir: File,
    version: String,
    exactOnly: Boolean,
    ignorePrefix: Boolean = false,
  ): LocalBundle? {
    val metaFile = File(dir, "meta.json")
    val buildabilityFile = File(dir, "buildability.json")
    val artifactsFile = File(dir, "artifacts.json")
    val loaderFile = File(dir, "loader-index.json")
    if (!metaFile.exists() || !artifactsFile.exists() || !loaderFile.exists()) {
      return null
    }
    val metaText = metaFile.readText()
    val meta = gson.fromJson(metaText, MetaV1::class.java)
    val metaVersion = meta.minecraft?.trim().orEmpty()
    if (metaVersion.isEmpty()) {
      return null
    }
    if (exactOnly) {
      if (metaVersion != version) {
        return null
      }
    } else if (!ignorePrefix && !metaVersion.startsWith("$version.")) {
      return null
    }
    val loader = gson.fromJson(loaderFile.readText(), Any::class.java)
    val artifacts = gson.fromJson(artifactsFile.readText(), Any::class.java)
    val metaJson = gson.fromJson(metaText, Any::class.java)
    val payload = gson.toJson(
      McmetaBundle(
        loaderIndex = loader,
        artifacts = artifacts,
        meta = metaJson,
        buildability = if (buildabilityFile.exists()) {
          gson.fromJson(buildabilityFile.readText(), Any::class.java)
        } else {
          null
        },
      )
    )
    return LocalBundle(
      payload = payload,
      version = metaVersion,
      exactMatch = exactOnly,
    )
  }

  private fun listBackupDirs(): List<File> {
    val baseCandidates = listOf(
      File(project.rootDir, "tools/mcmeta-harvest-backup"),
      File(project.rootDir, "../tools/mcmeta-harvest-backup"),
      File(project.rootDir, "../../tools/mcmeta-harvest-backup"),
      File(project.rootDir, "../../../tools/mcmeta-harvest-backup"),
    )
    return baseCandidates
      .filter { it.exists() && it.isDirectory }
      .flatMap { base ->
        base.listFiles()
          ?.filter { it.isDirectory }
          ?.sortedByDescending { it.name }
          ?: emptyList()
      }
  }

  private fun majorMinorPrefix(version: String): String? {
    val parts = version.trim().split('.')
    if (parts.size < 2) {
      return null
    }
    if (!parts[0].all { it.isDigit() } || !parts[1].all { it.isDigit() }) {
      return null
    }
    return "${parts[0]}.${parts[1]}"
  }

  private fun fetchLoomIndex(): LoomIndex? {
    val branch = extension.loomBranch.trim()
    if (branch.isEmpty()) {
      return null
    }
    val cacheFile = File(cacheDir, "loom-index.json")
    var payload: String? = if (cacheFile.exists()) cacheFile.readText() else null
    if (payload == null) {
      val localCandidates = listOf(
        File(project.rootDir, "web/mcmeta/loom-index.json"),
        File(project.rootDir, "../web/mcmeta/loom-index.json"),
        File(project.rootDir, "../../web/mcmeta/loom-index.json"),
        File(project.rootDir, "../../../web/mcmeta/loom-index.json"),
      )
      val localFile = localCandidates.firstOrNull { it.exists() }
      if (localFile != null) {
        payload = localFile.readText()
        cacheFile.writeText(payload)
      }
    }
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

  private fun fetchJsonOrNull(url: String): Any? {
    return try {
      fetchJson(url)
    } catch (_: Exception) {
      null
    }
  }

  private fun fetchProxyArtifacts(branch: String): Artifacts? {
    val artifactsUrl = "${extension.rawBase}/${extension.repo}/${branch}/artifacts.json"
    return try {
      val payload = fetchText(artifactsUrl)
      gson.fromJson(payload, Artifacts::class.java)
    } catch (err: Exception) {
      project.logger.warn("mcmeta: proxy artifacts fetch failed: ${err.message}")
      null
    }
  }

  private fun applyBundle(
    loaderIndex: LoaderIndex,
    artifacts: Artifacts,
    meta: MetaV1,
    buildability: BuildabilityV1?,
    version: String,
  ) {
    val loaders = loaderIndex.loaders
    val fabricLoader = loaders?.get("fabric")?.loader?.firstOrNull()
    val quiltLoader = loaders?.get("quilt")?.loader?.firstOrNull()
    val forgeLoader = loaders?.get("forge")?.loader?.firstOrNull()
    val neoforgeLoader = loaders?.get("neoforge")?.loader?.firstOrNull()

    val minestomRuntime = parseEntry(artifacts.runtimes, "minestom", MavenArtifact::class.java)
    val manifoldArtifact = parseEntry(artifacts.artifacts, "manifold", MavenArtifact::class.java)
    val fabricApiArtifact = parseEntry(artifacts.artifacts, "fabric-api", ModrinthArtifact::class.java)
    val paperArtifact = parseEntry(artifacts.artifacts, "paper", ProjectArtifact::class.java)
    val velocityArtifact = parseEntry(artifacts.artifacts, "velocity", ProjectArtifact::class.java)
    val foliaArtifact = parseEntry(artifacts.artifacts, "folia", ProjectArtifact::class.java)
    val purpurArtifact = parseEntry(artifacts.artifacts, "purpur", ProjectArtifact::class.java)
    val proxies = parseEntry(artifacts.artifacts, "proxies", Proxies::class.java)

    val minestomVersion = minestomRuntime?.versions?.firstOrNull()
    val manifoldVersion = manifoldArtifact?.versions?.firstOrNull()
    val fabricApiVersion = fabricApiArtifact?.versions?.firstOrNull()?.versionNumber
    val paperVersion = paperArtifact?.versions?.firstOrNull()
    val velocityGroups = proxies?.velocity?.groups ?: emptyList()
    val bungeecordGroups = proxies?.bungeecord?.groups ?: emptyList()
    val velocityVersions = if (velocityGroups.isNotEmpty()) {
      velocityGroups.flatMap { it.versions ?: emptyList() }
    } else {
      velocityArtifact?.versions ?: emptyList()
    }
    val velocityVersion = velocityVersions.firstOrNull()
    val bungeecordVersions = bungeecordGroups.flatMap { it.versions ?: emptyList() }
    val bungeecordVersion = bungeecordVersions.firstOrNull()
    val foliaVersion = foliaArtifact?.versions?.firstOrNull()
    val purpurVersion = purpurArtifact?.versions?.firstOrNull()
    val yarnLatest = meta.yarn?.latest
    val yarnVersions = meta.yarn?.versions ?: emptyList()
    val minecraftArtifact = parseEntry(artifacts.artifacts, "minecraft", MinecraftArtifacts::class.java)
    val derivedFabricBuildability = deriveFabricBuildability(
      version,
      fabricLoader,
      fabricApiVersion,
      yarnLatest,
      minecraftArtifact,
    )
    val fabricTarget = buildability?.targets?.get("fabric")

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
    extra.set(
      "mcmetaFabricBuildable",
      fabricTarget?.status?.equals("buildable", ignoreCase = true) ?: derivedFabricBuildability.buildable
    )
    extra.set(
      "mcmetaFabricBlockedBy",
      fabricTarget?.blockedBy ?: derivedFabricBuildability.blockedBy
    )
    extra.set(
      "mcmetaFabricMappingChannel",
      fabricTarget?.mappingChannel ?: derivedFabricBuildability.mappingChannel
    )
    extra.set(
      "mcmetaFabricMappingVersion",
      fabricTarget?.mappingVersion ?: derivedFabricBuildability.mappingVersion
    )
    extra.set(
      "mcmetaFabricAvailableMappingChannels",
      fabricTarget?.availableMappingChannels ?: derivedFabricBuildability.availableMappingChannels
    )

    extra.set("mcmetaFabricLoaderVersions", loaders?.get("fabric")?.loader ?: emptyList<String>())
    extra.set("mcmetaQuiltLoaderVersions", loaders?.get("quilt")?.loader ?: emptyList<String>())
    extra.set("mcmetaForgeVersions", loaders?.get("forge")?.loader ?: emptyList<String>())
    extra.set("mcmetaNeoForgeVersions", loaders?.get("neoforge")?.loader ?: emptyList<String>())
    extra.set("mcmetaMinestomVersions", minestomRuntime?.versions ?: emptyList<String>())
    extra.set("mcmetaManifoldVersions", manifoldArtifact?.versions ?: emptyList<String>())
    extra.set(
      "mcmetaFabricApiVersions",
      fabricApiArtifact?.versions?.map { it.versionNumber } ?: emptyList<String>()
    )
    extra.set("mcmetaPaperVersions", paperArtifact?.versions ?: emptyList<String>())
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
    extra.set("mcmetaFoliaVersions", foliaArtifact?.versions ?: emptyList<String>())
    extra.set("mcmetaPurpurVersions", purpurArtifact?.versions ?: emptyList<String>())
    applyTargetBuildabilityExtras(extra, "mcmetaQuilt", buildability?.targets?.get("quilt"), quiltLoader != null)
    applyTargetBuildabilityExtras(extra, "mcmetaForge", buildability?.targets?.get("forge"), forgeLoader != null)
    applyTargetBuildabilityExtras(extra, "mcmetaNeoForge", buildability?.targets?.get("neoforge"), neoforgeLoader != null)
    applyTargetBuildabilityExtras(extra, "mcmetaPaper", buildability?.targets?.get("paper"), paperVersion != null)
    applyTargetBuildabilityExtras(extra, "mcmetaVelocity", buildability?.targets?.get("velocity"), velocityVersion != null)
    applyTargetBuildabilityExtras(extra, "mcmetaFolia", buildability?.targets?.get("folia"), foliaVersion != null)
    applyTargetBuildabilityExtras(extra, "mcmetaPurpur", buildability?.targets?.get("purpur"), purpurVersion != null)
    applyTargetBuildabilityExtras(extra, "mcmetaMinestom", buildability?.targets?.get("minestom"), minestomVersion != null)
    extra.set("mcmetaFabricSupport", McmetaFabricSupport(project))
  }

  private fun <T> parseEntry(
    entries: Map<String, JsonObject>?,
    key: String,
    type: Class<T>,
  ): T? {
    val entry = entries?.get(key) ?: return null
    return gson.fromJson(entry, type)
  }

  private fun applyProxyFallbacks() {
    val extra = project.extensions.extraProperties
    val velocityVersion = extra.get("mcmetaVelocityVersion")?.toString()?.trim()
    val bungeecordVersion = extra.get("mcmetaBungeeCordVersion")?.toString()?.trim()
    if (!velocityVersion.isNullOrEmpty() && !bungeecordVersion.isNullOrEmpty()) {
      return
    }

    val velocityArtifacts = fetchProxyArtifacts("proxy/velocity-latest")
    val velocityProxies = parseEntry(velocityArtifacts?.artifacts, "proxies", Proxies::class.java)
    val velocityArtifact = parseEntry(velocityArtifacts?.artifacts, "velocity", ProjectArtifact::class.java)
    val velocityGroups = velocityProxies?.velocity?.groups ?: emptyList()
    val velocityVersions = if (velocityGroups.isNotEmpty()) {
      velocityGroups.flatMap { it.versions ?: emptyList() }
    } else {
      velocityArtifact?.versions ?: emptyList()
    }

    val bungeecordArtifacts = fetchProxyArtifacts("proxy/bungeecord-latest")
    val bungeecordProxies = parseEntry(bungeecordArtifacts?.artifacts, "proxies", Proxies::class.java)
    val bungeecordGroups = bungeecordProxies?.bungeecord?.groups ?: emptyList()
    val bungeecordVersions = bungeecordGroups.flatMap { it.versions ?: emptyList() }

    if (velocityVersion.isNullOrEmpty()) {
      val resolved = velocityVersions.firstOrNull()
      if (!resolved.isNullOrEmpty()) {
        extra.set("mcmetaVelocityVersion", resolved)
      }
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
    }

    if (bungeecordVersion.isNullOrEmpty()) {
      val resolved = bungeecordVersions.firstOrNull()
      if (!resolved.isNullOrEmpty()) {
        extra.set("mcmetaBungeeCordVersion", resolved)
      }
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
    }
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
      ?: parseEntry(artifacts.artifacts, "manifold", MavenArtifact::class.java)?.versions?.firstOrNull()
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

private fun extraStringOrNull(extra: ExtraPropertiesExtension, key: String): String? {
  if (!extra.has(key)) {
    return null
  }
  val raw = extra.get(key)?.toString()?.trim()
  return if (raw.isNullOrEmpty()) null else raw
}

private fun preferredExtraProperties(project: Project, key: String): ExtraPropertiesExtension {
  val projectExtra = project.extensions.extraProperties
  if (projectExtra.has(key)) {
    return projectExtra
  }
  return project.rootProject.extensions.extraProperties
}

private fun extraStringFromProjectOrRoot(project: Project, key: String): String? {
  val extra = preferredExtraProperties(project, key)
  return extraStringOrNull(extra, key)
}

private fun extraBoolean(extra: ExtraPropertiesExtension, key: String): Boolean {
  val value = extraStringOrNull(extra, key)
  return value?.equals("true", ignoreCase = true) == true
}

private fun extraList(extra: ExtraPropertiesExtension, key: String): List<String> {
  if (!extra.has(key)) {
    return emptyList()
  }
  val raw = extra.get(key)
  return when (raw) {
    is Iterable<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
    null -> emptyList()
    else -> listOf(raw.toString().trim()).filter { it.isNotEmpty() }
  }
}

private fun applyTargetBuildabilityExtras(
  extra: ExtraPropertiesExtension,
  prefix: String,
  target: BuildabilityTarget?,
  fallbackBuildable: Boolean,
) {
  val status = target?.status?.trim().orEmpty()
  val buildable = if (status.isNotEmpty()) status.equals("buildable", ignoreCase = true) else fallbackBuildable
  val blockedBy = target?.blockedBy?.trim().takeUnless { it.isNullOrEmpty() }
  extra.set("${prefix}Buildable", buildable)
  extra.set("${prefix}BlockedBy", blockedBy)
}

private fun deriveFabricBuildability(
  minecraftVersion: String,
  loaderVersion: String?,
  fabricApiVersion: String?,
  yarnVersion: String?,
  minecraftArtifact: MinecraftArtifacts?,
): DerivedFabricBuildability {
  val channels = mutableListOf<String>()
  val hasMojangMappings = minecraftArtifact?.clientMappings?.url?.isNotBlank() == true
    && minecraftArtifact.serverMappings?.url?.isNotBlank() == true
  if (hasMojangMappings) {
    channels.add("mojang")
  }
  if (!yarnVersion.isNullOrBlank()) {
    channels.add("yarn")
  }

  val selectedChannel = channels.firstOrNull()
  val mappingVersion = when (selectedChannel) {
    "mojang" -> minecraftVersion
    "yarn" -> yarnVersion
    else -> null
  }
  val blockedBy = when {
    loaderVersion.isNullOrBlank() -> "missing-fabric-loader"
    fabricApiVersion.isNullOrBlank() -> "missing-fabric-api"
    selectedChannel == null -> "missing-fabric-mappings"
    else -> null
  }

  return DerivedFabricBuildability(
    buildable = blockedBy == null,
    blockedBy = blockedBy,
    mappingChannel = selectedChannel,
    mappingVersion = mappingVersion,
    availableMappingChannels = channels,
  )
}

private data class McmetaBundle(
  val loaderIndex: Any,
  val artifacts: Any,
  val meta: Any,
  val buildability: Any? = null,
)

private data class DerivedFabricBuildability(
  val buildable: Boolean,
  val blockedBy: String?,
  val mappingChannel: String?,
  val mappingVersion: String?,
  val availableMappingChannels: List<String>,
)

private data class MojangManifest(
  val versions: List<MojangVersion>?,
  val latest: MojangLatest?,
)

private data class MetaV1(
  val schema: String?,
  @SerializedName("schema_version")
  val schemaVersion: Int?,
  val minecraft: String?,
  val sources: Map<String, String>?,
  val notes: List<String>?,
  val jdk: Int?,
  val yarn: YarnMeta?,
)

private data class BuildabilityV1(
  @SerializedName("schema_version")
  val schemaVersion: Int?,
  val targets: Map<String, BuildabilityTarget>?,
)

private data class BuildabilityTarget(
  val status: String?,
  @SerializedName("blocked_by")
  val blockedBy: String?,
  @SerializedName("mapping_channel")
  val mappingChannel: String?,
  @SerializedName("mapping_version")
  val mappingVersion: String?,
  @SerializedName("available_mapping_channels")
  val availableMappingChannels: List<String>?,
)

private data class MojangVersion(
  val id: String,
)

private data class MojangLatest(
  val release: String?,
  val snapshot: String?,
)

private data class LoaderIndex(
  @SerializedName("schema_version")
  val schemaVersion: Int?,
  val loaders: Map<String, LoaderFamily>?,
)

private data class LoaderFamily(
  val loader: List<String>?,
)

private data class Artifacts(
  @SerializedName("schema_version")
  val schemaVersion: Int?,
  val artifacts: Map<String, JsonObject>?,
  val runtimes: Map<String, JsonObject>?,
)

private data class MavenArtifact(
  val versions: List<String>?,
)

private data class MinecraftArtifacts(
  @SerializedName("client_mappings")
  val clientMappings: MinecraftDownload?,
  @SerializedName("server_mappings")
  val serverMappings: MinecraftDownload?,
)

private data class MinecraftDownload(
  val url: String?,
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
