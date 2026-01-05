@file:Suppress(
    "TooGenericExceptionCaught", // XML parsing and classpath scanning use catch-all for resilience
    "ReturnCount", // Multiple early returns are clearer in search/resolution methods
    "NestedBlockDepth", // Maven repository resolution has inherent complexity
)

package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovygdsl.GdslExecutor
import com.github.albertocavalcante.groovygdsl.GdslLoadResults
import com.github.albertocavalcante.groovygdsl.GdslLoader
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedParameter
import com.github.albertocavalcante.groovyjenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.VersionedMetadataLoader
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import com.github.albertocavalcante.groovyjenkins.plugins.PluginDiscoveryService
import com.github.albertocavalcante.groovyjenkins.scanning.JenkinsClasspathScanner
import com.github.albertocavalcante.groovyjenkins.stubs.JenkinsStubGenerator
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manages the Jenkins pipeline context, including classpath and GDSL metadata.
 * Keeps Jenkins-specific compilation separate from general Groovy sources.
 */
class JenkinsContext(
    private val configuration: JenkinsConfiguration,
    private val workspaceRoot: Path,
    private val pluginManager: JenkinsPluginManager = JenkinsPluginManager(),
) {
    private val logger = LoggerFactory.getLogger(JenkinsContext::class.java)
    private val libraryResolver = SharedLibraryResolver(configuration)
    private val gdslLoader = GdslLoader()
    private val gdslExecutor = GdslExecutor()
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
        classpath.tryGenerateStubs(logger, workspaceRoot, ::shouldGenerateStubs) { getAllMetadata() }

        return classpath
    }

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
                logger.warn("Could not determine Maven repository location")
                return null
            }

            val jenkinsCorePath = mavenRepo.resolve("org/jenkins-ci/main/jenkins-core")
            logger.info("Looking for jenkins-core in: $jenkinsCorePath")

            if (!Files.exists(jenkinsCorePath)) {
                logger.debug("jenkins-core directory not found at $jenkinsCorePath")
                return null
            }

            // Find the latest version directory (prefer semantic versioning)
            val versionDir = Files.list(jenkinsCorePath)
                .filter { Files.isDirectory(it) }
                .max { p1, p2 -> compareVersions(p1.fileName.toString(), p2.fileName.toString()) }
                .orElse(null)

            if (versionDir == null) {
                logger.warn("No version directories found in $jenkinsCorePath")
                return null
            }
            logger.info("Selected jenkins-core version: ${versionDir.fileName}")

            // Find the main jar (not sources/javadoc)
            val jar = Files.list(versionDir)
                .filter { path ->
                    val name = path.fileName.toString()
                    name.endsWith(".jar") &&
                        !name.endsWith("-sources.jar") &&
                        !name.endsWith("-javadoc.jar") &&
                        !name.endsWith("-tests.jar")
                }
                .findFirst()
                .orElse(null)

            if (jar == null) {
                logger.warn("No JAR found in $versionDir")
                return null
            }

            logger.info("Found jenkins-core JAR: $jar")
            return jar
        } catch (e: Exception) {
            logger.warn("Failed to lookup local jenkins-core", e)
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
                logger.debug("Using M2_REPO environment variable: $envPath")
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
                        logger.debug("Using localRepository from settings.xml: $customPath")
                        return customPath
                    }
                }
            } catch (e: Exception) {
                logger.debug("Could not parse settings.xml: ${e.message}")
            }
        }

        // 3. Default location
        val defaultRepo = m2Dir.resolve("repository")
        if (Files.exists(defaultRepo)) {
            logger.debug("Using default Maven repository: $defaultRepo")
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
     * Loads and executes GDSL metadata files from configured paths.
     */
    fun loadGdslMetadata(): GdslLoadResults {
        val results = gdslLoader.loadAllGdslFiles(configuration.gdslPaths)

        results.failed.forEach { result ->
            logger.warn("Failed to load Jenkins GDSL: ${result.path} - ${result.error}")
        }

        if (!configuration.gdslExecutionEnabled) {
            if (results.successful.isNotEmpty()) {
                logger.warn(
                    "GDSL execution disabled; loaded {} files but skipping execution. " +
                        "Enable with jenkins.gdslExecution.enabled=true to allow script execution.",
                    results.successful.size,
                )
            }

            return results
        }

        // Log results and execute successful loads
        results.successful.forEach { result ->
            logger.info("Loaded Jenkins GDSL: ${result.path}")
            result.content?.let { content ->
                try {
                    gdslExecutor.execute(content, result.path)
                } catch (e: Exception) {
                    logger.error("Failed to execute GDSL: ${result.path}", e)
                }
            }
        }

        return results
    }

    /**
     * Checks if a URI is a Jenkins pipeline file based on configured patterns.
     */
    fun isJenkinsFile(uri: java.net.URI): Boolean = fileDetector.isJenkinsFile(uri)

    private val scanner = JenkinsClasspathScanner()
    private val dynamicMetadataCache =
        mutableMapOf<Int, BundledJenkinsMetadata>()
    private var currentClasspathHash: Int = 0

    /**
     * Get combined metadata (bundled + dynamic), optionally filtered by installed plugins.
     *
     * HEURISTIC: If plugins.txt exists, filter to only show steps from installed plugins.
     * If no plugins.txt, show all bundled metadata (better UX for users without JCasC).
     */
    fun getAllMetadata(): MergedJenkinsMetadata {
        // 1. Load base bundled/versioned metadata
        val loader = VersionedMetadataLoader()
        val versionedMerged = loader.loadMerged() // This is MergedJenkinsMetadata (Context-rich)
        // We need Bundled for merging... VersionedMetadataLoader.loadMerged() returns MergedJenkinsMetadata.
        // We might need to step back and use MetadataMerger capabilities directly.

        // Let's get the raw bundled/versioned first if possible or just use the merged and overlay dynamic.
        // Since MergedJenkinsMetadata is the target, we can overlay dynamic steps onto it.

        val dynamicMetadata = dynamicMetadataCache[currentClasspathHash]

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
        logger.debug("Filtering metadata to {} installed plugins", installedPlugins.size)
        return finalMetadata.copy(
            steps = finalMetadata.steps.filterValues { step ->
                step.plugin?.let { it in installedPlugins } ?: false
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
     */
    fun scanClasspath(classpath: List<Path>) {
        val newHash = classpath.hashCode()
        if (newHash == currentClasspathHash && dynamicMetadataCache.containsKey(newHash)) {
            logger.debug("Classpath hash unchanged ($newHash), skipping scan")
            return
        }

        try {
            logger.info("Scanning {} classpath entries for Jenkins metadata (hash: {})", classpath.size, newHash)
            val metadata = scanner.scan(classpath)
            dynamicMetadataCache[newHash] = metadata
            currentClasspathHash = newHash
        } catch (e: Exception) {
            logger.error("Failed to scan classpath", e)
        }
    }

    /**
     * Parses library references from Jenkinsfile source.
     */
    fun parseLibraries(source: String): List<LibraryReference> = libraryParser.parseLibraries(source)
}

private fun MutableList<Path>.addProjectDependencies(logger: Logger, projectDependencies: List<Path>) {
    if (projectDependencies.isEmpty()) return

    addAll(projectDependencies)
    logger.debug("Added ${projectDependencies.size} project dependencies to Jenkins classpath")
}

private fun MutableList<Path>.ensureJenkinsCorePresent(logger: Logger, findLocalJenkinsCore: () -> Path?) {
    val jenkinsCorePattern = Regex("""^jenkins-core-\d+(\.\d+)*\.jar$""")
    val existingCore = find { jenkinsCorePattern.matches(it.fileName.toString()) }

    if (existingCore != null) {
        logger.info("Found existing jenkins-core candidate: $existingCore")
        return
    }

    logger.info("No jenkins-core found in project dependencies")
    findLocalJenkinsCore()?.let {
        add(it)
        logger.info("Auto-injected jenkins-core from local repository: $it")
    }
}

private fun resolveLibrariesToInclude(
    configuration: JenkinsConfiguration,
    libraryResolver: SharedLibraryResolver,
    logger: Logger,
    libraryReferences: List<LibraryReference>,
): List<SharedLibrary> = if (libraryReferences.isEmpty()) {
    configuration.sharedLibraries
} else {
    val result = libraryResolver.resolveAllWithWarnings(libraryReferences)
    result.missing.forEach { ref ->
        logger.warn("Jenkins library '${ref.name}' referenced but not configured")
    }
    result.resolved
}

private fun MutableList<Path>.addLibrariesToClasspath(logger: Logger, librariesToInclude: List<SharedLibrary>) {
    librariesToInclude.forEach { library ->
        val jarPath = Paths.get(library.jar)
        if (Files.exists(jarPath)) {
            add(jarPath)
            logger.debug("Added Jenkins library jar to classpath: ${library.jar}")
        } else {
            logger.warn("Jenkins library jar not found: ${library.jar}")
        }

        library.sourcesJar?.let { sourcesJar ->
            val sourcesPath = Paths.get(sourcesJar)
            if (Files.exists(sourcesPath)) {
                add(sourcesPath)
                logger.debug("Added Jenkins library sources to classpath: $sourcesJar")
            } else {
                logger.debug("Jenkins library sources jar not found: $sourcesJar")
            }
        }
    }
}

private fun MutableList<Path>.addSharedLibrarySrcDir(logger: Logger, workspaceRoot: Path) {
    val srcDir = workspaceRoot.resolve("src")
    if (Files.exists(srcDir) && Files.isDirectory(srcDir)) {
        add(srcDir)
        logger.debug("Added Jenkins Shared Library 'src' directory to classpath: $srcDir")
    }
}

private fun MutableList<Path>.addRegisteredPluginJars(logger: Logger, pluginManager: JenkinsPluginManager) {
    runBlocking {
        val pluginJars = pluginManager.getRegisteredPluginJars()
        pluginJars.forEach { jar ->
            if (Files.exists(jar) && !contains(jar)) {
                add(jar)
                logger.debug("Added registered plugin JAR to classpath: $jar")
            }
        }
    }
}

private fun MutableList<Path>.tryGenerateStubs(
    logger: Logger,
    workspaceRoot: Path,
    shouldGenerateStubs: (List<Path>) -> Boolean,
    getAllMetadata: () -> MergedJenkinsMetadata,
) {
    runCatching {
        val stubsDir = workspaceRoot.resolve(".jenkins-stubs")
        if (!shouldGenerateStubs(this)) return@runCatching

        logger.info("Generating Jenkins plugin stubs in $stubsDir")
        val stubGenerator = JenkinsStubGenerator()
        val metadata = getAllMetadata()
        stubGenerator.generateStubs(metadata, stubsDir)
        add(stubsDir)
    }.onFailure { throwable ->
        if (throwable is Error) throw throwable
        logger.warn("Failed to generate Jenkins stubs", throwable)
    }
}
