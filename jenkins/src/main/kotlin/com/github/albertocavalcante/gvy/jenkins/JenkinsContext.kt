@file:Suppress(
    "TooGenericExceptionCaught", // XML parsing and classpath scanning use catch-all for resilience
    "ReturnCount", // Multiple early returns are clearer in search/resolution methods
    "NestedBlockDepth", // Maven repository resolution has inherent complexity
)

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.gdsl.GdslExecutor
import com.github.albertocavalcante.gvy.gdsl.GdslLoadResults
import com.github.albertocavalcante.gvy.gdsl.GdslLoader
import com.github.albertocavalcante.gvy.jenkins.gdsl.JenkinsGdslAdapter
import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.VersionedMetadataLoader
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import com.github.albertocavalcante.gvy.jenkins.plugins.PluginDiscoveryService
import com.github.albertocavalcante.gvy.jenkins.scanning.JenkinsClasspathScanner
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubGenerator
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubsLocation
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test seams for stub generation. Production code uses the defaults.
 *
 * @param warn receives warnings about workspace state; `null` logs them through the context logger.
 */
internal class StubHooks(
    val cacheRoot: Path? = null,
    val generator: JenkinsStubGenerator = JenkinsStubGenerator(),
    val defaultCacheRoot: () -> Path = JenkinsStubsLocation::defaultCacheRoot,
    val warn: ((String) -> Unit)? = null,
)

/**
 * Manages the Jenkins pipeline context, including classpath and GDSL metadata.
 * Keeps Jenkins-specific compilation separate from general Groovy sources.
 *
 * Generated stubs are never written inside [workspaceRoot].
 */
class JenkinsContext internal constructor(
    private val configuration: JenkinsConfiguration,
    private val workspaceRoot: Path,
    private val pluginManager: JenkinsPluginManager,
    bundledMetadataLoader: () -> MergedJenkinsMetadata,
    private val classpathScanner: (List<Path>) -> BundledJenkinsMetadata,
    private val stubHooks: StubHooks = StubHooks(),
) {
    /**
     * @param stubsCacheRoot root for generated stubs; defaults to [JenkinsStubsLocation.defaultCacheRoot].
     */
    constructor(
        configuration: JenkinsConfiguration,
        workspaceRoot: Path,
        pluginManager: JenkinsPluginManager = JenkinsPluginManager(),
        stubsCacheRoot: Path? = null,
    ) : this(
        configuration = configuration,
        workspaceRoot = workspaceRoot,
        pluginManager = pluginManager,
        bundledMetadataLoader = { sharedBundledMetadata },
        classpathScanner = JenkinsClasspathScanner()::scan,
        stubHooks = StubHooks(cacheRoot = stubsCacheRoot),
    )

    private val logger = KotlinLogging.logger {}
    private val warn: (String) -> Unit = stubHooks.warn ?: { message -> logger.warn { message } }
    private val libraryResolver = SharedLibraryResolver(configuration)
    private val gdslLoader = GdslLoader()
    private val gdslExecutor = GdslExecutor()
    private val gdslAdapter = JenkinsGdslAdapter()
    private val fileDetector = JenkinsFileDetector(configuration.filePatterns)
    private val libraryParser = LibraryParser()
    private val pluginDiscovery = PluginDiscoveryService(workspaceRoot, configuration.pluginConfig)

    /**
     * Builds the classpath for Jenkins pipeline files based on library references.
     * If no references are provided, includes all configured libraries.
     */
    fun buildClasspath(
        libraryReferences: List<LibraryReference>,
        projectDependencies: List<Path> = emptyList(),
    ): List<Path> {
        val classpath = mutableListOf<Path>()

        classpath.addProjectDependencies(logger, projectDependencies)
        classpath.ensureJenkinsCorePresent(logger) { findLocalJenkinsCore() }

        val librariesToInclude = resolveLibrariesToInclude(configuration, libraryResolver, logger, libraryReferences)
        classpath.addLibrariesToClasspath(logger, librariesToInclude)
        classpath.addSharedLibrarySrcDir(logger, workspaceRoot)
        classpath.addRegisteredPluginJars(logger, pluginManager)

        scanClasspath(classpath)
        warnAboutLegacyStubsDir()
        classpath.tryGenerateStubs(logger, stubHooks.generator, ::stubsDirectory, ::shouldGenerateStubs) {
            getAllMetadata()
        }

        return classpath
    }

    /**
     * Older versions generated stubs into the workspace; point users at the leftover dir once per
     * workspace per process. Must not resolve the cache root: that can fail, and this runs outside
     * the guarded stub generation.
     */
    private fun warnAboutLegacyStubsDir() {
        val legacy = JenkinsStubsLocation.legacyWorkspaceDir(workspaceRoot) ?: return
        if (JenkinsStubsLocation.claimLegacyWarning(workspaceRoot)) {
            warn(JenkinsStubsLocation.legacyStubsWarning(legacy))
        }
    }

    private fun stubsDirectory(): Path =
        JenkinsStubsLocation.directoryFor(stubHooks.cacheRoot ?: stubHooks.defaultCacheRoot(), workspaceRoot)

    private fun shouldGenerateStubs(classpath: List<Path>): Boolean {
        // Heuristic: If we don't have workflow-cps jar, we definitely need stubs for CpsScript
        val hasWorkflowCps = classpath.any { it.fileName.toString().contains("workflow-cps") }
        return !hasWorkflowCps
    }

    /**
     * Attempts to find a jenkins-core JAR in the local Maven repository.
     * Uses proper Maven repository detection supporting:
     * - M2_REPO environment variable
     * - Custom localRepository in settings.xml
     * - Cross-platform default paths
     */
    private fun findLocalJenkinsCore(): Path? {
        try {
            val mavenRepo = resolveMavenRepository()
            if (mavenRepo == null) {
                logger.warn { "Could not determine Maven repository location" }
                return null
            }

            val jenkinsCorePath = mavenRepo.resolve("org/jenkins-ci/main/jenkins-core")
            logger.info { "Looking for jenkins-core in: $jenkinsCorePath" }

            if (!Files.exists(jenkinsCorePath)) {
                logger.debug { "jenkins-core directory not found at $jenkinsCorePath" }
                return null
            }

            // Find the latest version directory (prefer semantic versioning)
            val versionDir = Files.list(jenkinsCorePath).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .max { p1, p2 -> compareVersions(p1.fileName.toString(), p2.fileName.toString()) }
                    .orElse(null)
            }

            if (versionDir == null) {
                logger.warn { "No version directories found in $jenkinsCorePath" }
                return null
            }
            logger.info { "Selected jenkins-core version: ${versionDir.fileName}" }

            // Find the main jar (not sources/javadoc)
            val jar = Files.list(versionDir).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.endsWith(".jar") &&
                        !name.endsWith("-sources.jar") &&
                        !name.endsWith("-javadoc.jar") &&
                        !name.endsWith("-tests.jar")
                }
                    .findFirst()
                    .orElse(null)
            }

            if (jar == null) {
                logger.warn { "No JAR found in $versionDir" }
                return null
            }

            logger.info { "Found jenkins-core JAR: $jar" }
            return jar
        } catch (e: Exception) {
            logger.warn(e) { "Failed to lookup local jenkins-core" }
            return null
        }
    }

    /**
     * Resolves the Maven local repository path using standard Maven resolution order:
     * 1. M2_REPO environment variable
     * 2. localRepository in ~/.m2/settings.xml
     * 3. Default: ~/.m2/repository
     */
    private fun resolveMavenRepository(): Path? {
        // 1. Check M2_REPO environment variable
        val m2RepoEnv = System.getenv("M2_REPO")
        if (!m2RepoEnv.isNullOrBlank()) {
            val envPath = Paths.get(m2RepoEnv)
            if (Files.exists(envPath)) {
                logger.debug { "Using M2_REPO environment variable: $envPath" }
                return envPath
            }
        }

        // Get user home (works cross-platform)
        val userHome = System.getProperty("user.home") ?: return null
        val m2Dir = Paths.get(userHome, ".m2")

        // 2. Check settings.xml for localRepository using proper XML parsing
        val settingsFile = m2Dir.resolve("settings.xml").toFile()
        if (settingsFile.exists()) {
            try {
                val dbf = DocumentBuilderFactory.newInstance()
                dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                val db = dbf.newDocumentBuilder()
                val nodeList = db.parse(settingsFile).getElementsByTagName("localRepository")
                if (nodeList.length > 0) {
                    val repoPath = nodeList.item(0).textContent.trim()
                    val customPath = Paths.get(repoPath)
                    if (Files.exists(customPath)) {
                        logger.debug { "Using localRepository from settings.xml: $customPath" }
                        return customPath
                    }
                }
            } catch (e: Exception) {
                logger.debug { "Could not parse settings.xml: ${e.message}" }
            }
        }

        // 3. Default location
        val defaultRepo = m2Dir.resolve("repository")
        if (Files.exists(defaultRepo)) {
            logger.debug { "Using default Maven repository: $defaultRepo" }
            return defaultRepo
        }

        return null
    }

    /**
     * Compares version strings with semantic versioning awareness.
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split("[.-]".toRegex())
        val parts2 = v2.split("[.-]".toRegex())

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val p2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    /**
     * Loads the configured GDSL files and publishes the steps and global variables they declare, replacing
     * those of the previous load (this is also the reload path). GDSL is read from its AST, never run.
     * A file that fails to load or read is logged and skipped; the other files still contribute.
     */
    @Synchronized
    fun loadGdslMetadata(): GdslLoadResults {
        val results = gdslLoader.loadAllGdslFiles(configuration.gdslPaths)

        results.failed.forEach { result ->
            logger.warn { "Failed to load Jenkins GDSL: ${result.path} - ${result.error}" }
        }

        if (!configuration.gdslExecutionEnabled) {
            if (results.successful.isNotEmpty()) {
                logger.warn {
                    "GDSL execution disabled; loaded ${results.successful.size} files but skipping execution. " +
                        "Enable with jenkins.gdslExecution.enabled=true to allow script execution."
                }
            }

            gdslSnapshot = null
            return results
        }

        gdslSnapshot = GdslSnapshot(readGdsl(results))
        return results
    }

    /** Contributions of every readable file; a later file's step or global replaces an earlier one's. */
    private fun readGdsl(results: GdslLoadResults): BundledJenkinsMetadata {
        val steps = mutableMapOf<String, JenkinsStepMetadata>()
        val globals = mutableMapOf<String, GlobalVariableMetadata>()
        results.successful.forEach { result ->
            val content = result.content ?: return@forEach
            val parsed = gdslExecutor.executeAndCapture(content, result.path)
            if (!parsed.success) {
                logger.warn { "Could not read Jenkins GDSL ${result.path}: ${parsed.error}" }
                return@forEach
            }
            parsed.warnings.forEach { logger.warn { "Jenkins GDSL ${result.path}: $it" } }
            val metadata = gdslAdapter.convert(parsed)
            steps += metadata.steps
            globals += metadata.globalVariables
            logger.info { "Loaded Jenkins GDSL ${result.path}: ${metadata.steps.size} steps" }
        }
        return BundledJenkinsMetadata(steps = steps, globalVariables = globals)
    }

    /**
     * Checks if a URI is a Jenkins pipeline file based on configured patterns.
     */
    fun isJenkinsFile(uri: java.net.URI): Boolean = fileDetector.isJenkinsFile(uri)

    /** Bundled base for this context, loaded on first use and never rebuilt. */
    private val bundledMetadata: MergedJenkinsMetadata by lazy(bundledMetadataLoader)

    /** Result of the latest successful classpath scan; replaced atomically by [scanClasspath]. */
    private class ScanSnapshot(val classpath: List<Path>, val metadata: BundledJenkinsMetadata)

    /** Metadata declared by the configured GDSL files; replaced atomically by [loadGdslMetadata]. */
    private class GdslSnapshot(val metadata: BundledJenkinsMetadata)

    /** Merged view computed for one scan and GDSL snapshot (null = that source contributes nothing). */
    private class MergedSnapshot(val scan: ScanSnapshot?, val gdsl: GdslSnapshot?, val metadata: MergedJenkinsMetadata)

    @Volatile
    private var scanSnapshot: ScanSnapshot? = null

    @Volatile
    private var gdslSnapshot: GdslSnapshot? = null

    @Volatile
    private var mergedSnapshot: MergedSnapshot? = null

    /**
     * Get combined metadata (bundled + dynamic), optionally filtered by installed plugins.
     *
     * Completion and hover call this per request, so the result is memoized: it is rebuilt only
     * when a classpath scan or a GDSL (re)load publishes new metadata. Configuration (plugins, GDSL
     * paths) is fixed per context; a configuration change creates a new context and therefore a new view.
     *
     * HEURISTIC: If plugins.txt exists, filter to only show steps from installed plugins.
     * If no plugins.txt, show all bundled metadata (better UX for users without JCasC).
     */
    fun getAllMetadata(): MergedJenkinsMetadata {
        val scan = scanSnapshot
        val gdsl = gdslSnapshot
        mergedSnapshot?.takeIf { it.scan === scan && it.gdsl === gdsl }?.let { return it.metadata }

        // Racing callers may compute the same view twice; both results are equal and the
        // expensive bundled load is guarded by its own lazy initializer.
        val merged = withGdsl(buildMergedMetadata(scan?.metadata), gdsl?.metadata)
        mergedSnapshot = MergedSnapshot(scan, gdsl, merged)
        return merged
    }

    /**
     * Adds GDSL steps and globals whose names the other sources do not know, as either a step or a global, so
     * completion never offers a name twice. Bundled and scanned metadata are richer (enrichment, parameters,
     * plugins), so GDSL never replaces them; a GDSL name declared as both a step and a global stays a step.
     * GDSL entries are the user's own configuration, so plugin filtering does not apply to them.
     */
    private fun withGdsl(metadata: MergedJenkinsMetadata, gdsl: BundledJenkinsMetadata?): MergedJenkinsMetadata {
        if (gdsl == null) return metadata
        val known = metadata.steps.keys + metadata.globalVariables.keys
        val newSteps = gdsl.steps.filterKeys { it !in known }.mapValues { (_, step) -> step.toMerged() }
        val newGlobals = gdsl.globalVariables.filterKeys { it !in known && it !in gdsl.steps }
            .mapValues { (_, global) -> global.toMerged() }
        if (newSteps.isEmpty() && newGlobals.isEmpty()) return metadata
        return metadata.copy(steps = metadata.steps + newSteps, globalVariables = metadata.globalVariables + newGlobals)
    }

    private fun GlobalVariableMetadata.toMerged(): MergedGlobalVariable = MergedGlobalVariable(
        name = name,
        type = type,
        extractedDocumentation = documentation,
        enrichedDescription = null,
        documentationUrl = null,
        properties = emptyMap(),
    )

    private fun buildMergedMetadata(dynamicMetadata: BundledJenkinsMetadata?): MergedJenkinsMetadata {
        val versionedMerged = bundledMetadata

        val finalMetadata = if (dynamicMetadata != null) {
            val combinedSteps = versionedMerged.steps + dynamicMetadata.steps.mapValues { (name, step) ->
                step.toMerged(backup = versionedMerged.steps[name])
            }
            versionedMerged.copy(steps = combinedSteps)
        } else {
            versionedMerged
        }

        // Apply plugin filtering ONLY if explicit configuration exists (not just defaults)
        // HEURISTIC: If user hasn't configured plugins.txt or jenkins.plugins, show all bundled
        if (!pluginDiscovery.hasPluginConfiguration()) {
            return finalMetadata
        }

        // Filter metadata to only installed plugins (including defaults if enabled)
        val installedPlugins = pluginDiscovery.getInstalledPluginNames()
        logger.debug { "Filtering metadata to ${installedPlugins.size} installed plugins" }
        return finalMetadata.copy(
            steps = finalMetadata.steps.filterValues { step ->
                step.plugin?.let { it in installedPlugins } ?: false
            },
            // Core entries (cron, string, maven, ...) are always available; plugin ones (gradle) need the plugin.
            directiveEntries = finalMetadata.directiveEntries.mapValues { (_, entries) ->
                entries.filterValues { it.plugin == JENKINS_CORE_PLUGIN || it.plugin in installedPlugins }
            },
        )
    }

    private fun JenkinsStepMetadata.toMerged(backup: MergedStepMetadata? = null): MergedStepMetadata =
        MergedStepMetadata(
            name = this.name,
            scope = StepScope.GLOBAL,
            positionalParams = this.positionalParams,
            namedParams = run
            {
                val dynamicParams = this.parameters.mapValues { (pName, param) ->
                    MergedParameter(
                        name = pName,
                        type = param.type,
                        defaultValue = param.default,
                        description = param.documentation,
                        required = param.required,
                        validValues = null,
                        examples = emptyList(),
                    )
                }
                if (backup != null) {
                    backup.namedParams + dynamicParams
                } else {
                    dynamicParams
                }
            },
            extractedDocumentation = this.documentation,
            returnType = null,
            plugin = this.plugin,
            // Preserve enrichment from backup if available
            enrichedDescription = backup?.enrichedDescription,
            documentationUrl = backup?.documentationUrl,
            category = backup?.category,
            examples = backup?.examples ?: emptyList(),
            deprecation = backup?.deprecation,
        )

    /**
     * Parse and scan classpath for Jenkins metadata.
     *
     * Rescans only when the classpath differs from the last scan. The production scanner
     * ([JenkinsClasspathScanner.scan]) never throws: an unreadable jar yields empty metadata, which is
     * published like any other result. Only an injected scanner that throws leaves the previous
     * snapshot in place.
     */
    @Synchronized
    fun scanClasspath(classpath: List<Path>) {
        if (scanSnapshot?.classpath == classpath) {
            logger.debug { "Classpath unchanged (${classpath.size} entries), skipping scan" }
            return
        }

        try {
            logger.info { "Scanning ${classpath.size} classpath entries for Jenkins metadata" }
            val snapshot = classpath.toList()
            scanSnapshot = ScanSnapshot(snapshot, classpathScanner(snapshot))
        } catch (e: Exception) {
            logger.error(e) { "Failed to scan classpath" }
        }
    }

    /**
     * Parses library references from Jenkinsfile source.
     */
    fun parseLibraries(source: String): List<LibraryReference> = libraryParser.parseLibraries(source)

    companion object {
        /** Plugin id that bundled metadata uses for Jenkins core contributions. */
        private const val JENKINS_CORE_PLUGIN = "jenkins-core"

        /**
         * Bundled and enrichment metadata come from immutable classpath resources, so every
         * context in the process shares one deserialized and merged copy.
         */
        internal val sharedBundledMetadata: MergedJenkinsMetadata by lazy { VersionedMetadataLoader().loadMerged() }
    }
}

private fun MutableList<Path>.addProjectDependencies(logger: KLogger, projectDependencies: List<Path>) {
    if (projectDependencies.isEmpty()) return

    addAll(projectDependencies)
    logger.debug { "Added ${projectDependencies.size} project dependencies to Jenkins classpath" }
}

private fun MutableList<Path>.ensureJenkinsCorePresent(logger: KLogger, findLocalJenkinsCore: () -> Path?) {
    val jenkinsCorePattern = Regex("""^jenkins-core-\d+(\.\d+)*\.jar$""")
    val existingCore = find { jenkinsCorePattern.matches(it.fileName.toString()) }

    if (existingCore != null) {
        logger.info { "Found existing jenkins-core candidate: $existingCore" }
        return
    }

    logger.info { "No jenkins-core found in project dependencies" }
    findLocalJenkinsCore()?.let {
        add(it)
        logger.info { "Auto-injected jenkins-core from local repository: $it" }
    }
}

private fun resolveLibrariesToInclude(
    configuration: JenkinsConfiguration,
    libraryResolver: SharedLibraryResolver,
    logger: KLogger,
    libraryReferences: List<LibraryReference>,
): List<SharedLibrary> = if (libraryReferences.isEmpty()) {
    configuration.sharedLibraries
} else {
    val result = libraryResolver.resolveAllWithWarnings(libraryReferences)
    result.missing.forEach { ref ->
        logger.warn { "Jenkins library '${ref.name}' referenced but not configured" }
    }
    result.resolved
}

private fun MutableList<Path>.addLibrariesToClasspath(logger: KLogger, librariesToInclude: List<SharedLibrary>) {
    librariesToInclude.forEach { library ->
        val jarPath = Paths.get(library.jar)
        if (Files.exists(jarPath)) {
            add(jarPath)
            logger.debug { "Added Jenkins library jar to classpath: ${library.jar}" }
        } else {
            logger.warn { "Jenkins library jar not found: ${library.jar}" }
        }

        library.sourcesJar?.let { sourcesJar ->
            val sourcesPath = Paths.get(sourcesJar)
            if (Files.exists(sourcesPath)) {
                add(sourcesPath)
                logger.debug { "Added Jenkins library sources to classpath: $sourcesJar" }
            } else {
                logger.debug { "Jenkins library sources jar not found: $sourcesJar" }
            }
        }
    }
}

private fun MutableList<Path>.addSharedLibrarySrcDir(logger: KLogger, workspaceRoot: Path) {
    val srcDir = workspaceRoot.resolve("src")
    if (Files.exists(srcDir) && Files.isDirectory(srcDir)) {
        add(srcDir)
        logger.debug { "Added Jenkins Shared Library 'src' directory to classpath: $srcDir" }
    }
}

private fun MutableList<Path>.addRegisteredPluginJars(logger: KLogger, pluginManager: JenkinsPluginManager) {
    runBlocking {
        val pluginJars = pluginManager.getRegisteredPluginJars()
        pluginJars.forEach { jar ->
            if (Files.exists(jar) && !contains(jar)) {
                add(jar)
                logger.debug { "Added registered plugin JAR to classpath: $jar" }
            }
        }
    }
}

private fun MutableList<Path>.tryGenerateStubs(
    logger: KLogger,
    stubGenerator: JenkinsStubGenerator,
    stubsDirectory: () -> Path,
    shouldGenerateStubs: (List<Path>) -> Boolean,
    getAllMetadata: () -> MergedJenkinsMetadata,
) {
    tryGenerateLibraryAnnotationStub(logger, stubGenerator, stubsDirectory)

    runCatching {
        if (!shouldGenerateStubs(this)) return@runCatching
        val stubsDir = stubsDirectory()

        logger.info { "Generating Jenkins plugin stubs in $stubsDir" }
        val metadata = getAllMetadata()
        try {
            stubGenerator.generateStubs(metadata, stubsDir)
        } catch (e: IOException) {
            // A failed rewrite (e.g. a stub held open on Windows) must not drop a complete earlier set.
            if (!JenkinsStubGenerator.hasCompleteStubs(stubsDir)) throw e
            logger.warn(e) { "Failed to regenerate Jenkins stubs in $stubsDir; reusing the previous complete set" }
        }
        add(stubsDir)
    }.onFailure { throwable ->
        if (throwable is Error) throw throwable
        logger.warn(throwable) { "Failed to generate Jenkins stubs" }
    }
}

/**
 * Always needed: no plugin jar supplies the implicit `@Library` import (see
 * [JenkinsStubGenerator.generateLibraryAnnotation]). It lives in its own `implicit` directory so the
 * plugin stubs stay off the classpath when the real plugins are present. Like the plugin stubs, a
 * stub left by an earlier run keeps serving when rewriting it fails.
 */
private fun MutableList<Path>.tryGenerateLibraryAnnotationStub(
    logger: KLogger,
    stubGenerator: JenkinsStubGenerator,
    stubsDirectory: () -> Path,
) {
    runCatching {
        val implicitStubsDir = stubsDirectory().resolve(IMPLICIT_STUBS_DIR)
        try {
            stubGenerator.generateLibraryAnnotation(implicitStubsDir)
        } catch (e: IOException) {
            if (!JenkinsStubGenerator.hasLibraryAnnotationStub(implicitStubsDir)) throw e
            logger.warn(e) { "Failed to regenerate the Jenkins @Library stub; reusing the previous one" }
        }
        add(implicitStubsDir)
    }.onFailure { throwable ->
        if (throwable is Error) throw throwable
        logger.warn(throwable) { "Failed to generate the Jenkins @Library annotation stub" }
    }
}

/** Subdirectory of the stubs directory holding stubs that are needed even with the real plugins. */
private const val IMPLICIT_STUBS_DIR = "implicit"
