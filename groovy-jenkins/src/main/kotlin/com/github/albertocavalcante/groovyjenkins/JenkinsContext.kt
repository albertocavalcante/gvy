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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Manages the Jenkins pipeline context, including classpath and GDSL metadata.
 * Keeps Jenkins-specific compilation separate from general Groovy sources.
 */
class JenkinsContext(private val configuration: JenkinsConfiguration, private val workspaceRoot: Path) {
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

        // Add dependencies resolved from the project build tool (Maven/Gradle)
        // This is where Jenkins Core/Plugins should come from if the project defines them
        if (projectDependencies.isNotEmpty()) {
            classpath.addAll(projectDependencies)
            logger.debug("Added ${projectDependencies.size} project dependencies to Jenkins classpath")
        }

        // Check if jenkins-core is already present (use precise pattern to avoid false positives)
        val jenkinsCorePattern = Regex("""^jenkins-core-\d+(\.\d+)*\.jar$""")
        val existingCore = classpath.find { jenkinsCorePattern.matches(it.fileName.toString()) }
        if (existingCore != null) {
            logger.info("Found existing jenkins-core candidate: $existingCore")
        } else {
            logger.info("No jenkins-core found in project dependencies")
            findLocalJenkinsCore()?.let {
                classpath.add(it)
                logger.info("Auto-injected jenkins-core from local repository: $it")
            }
        }

        // If no specific references, include all configured libraries
        val librariesToInclude = if (libraryReferences.isEmpty()) {
            configuration.sharedLibraries
        } else {
            // Resolve library references to actual jars
            val result = libraryResolver.resolveAllWithWarnings(libraryReferences)

            // Log warnings for missing libraries
            result.missing.forEach { ref ->
                logger.warn("Jenkins library '${ref.name}' referenced but not configured")
            }

            result.resolved
        }

        librariesToInclude.forEach { library ->
            // Add main jar
            val jarPath = Paths.get(library.jar)
            if (Files.exists(jarPath)) {
                classpath.add(jarPath)
                logger.debug("Added Jenkins library jar to classpath: ${library.jar}")
            } else {
                logger.warn("Jenkins library jar not found: ${library.jar}")
            }

            // Add sources jar if available
            library.sourcesJar?.let { sourcesJar ->
                val sourcesPath = Paths.get(sourcesJar)
                if (Files.exists(sourcesPath)) {
                    classpath.add(sourcesPath)
                    logger.debug("Added Jenkins library sources to classpath: $sourcesJar")
                } else {
                    logger.debug("Jenkins library sources jar not found: $sourcesJar")
                }
            }
        }

        // Add 'src' folder if it exists (standard Jenkins Shared Library structure)
        val srcDir = workspaceRoot.resolve("src")
        if (Files.exists(srcDir) && Files.isDirectory(srcDir)) {
            classpath.add(srcDir)
            logger.debug("Added Jenkins Shared Library 'src' directory to classpath: $srcDir")
        }

        // Scan classpath for dynamic Jenkins definitions
        scanClasspath(classpath)

        // Generate and add partial stubs if full plugin support is missing
        // This ensures types like CpsScript (pipeline) are available even without downloading plugin JARs
        try {
            val stubsDir = workspaceRoot.resolve(".jenkins-stubs")
            if (shouldGenerateStubs(classpath)) {
                logger.info("Generating Jenkins plugin stubs in $stubsDir")
                val stubGenerator = com.github.albertocavalcante.groovyjenkins.stubs.JenkinsStubGenerator()
                // Load merged metadata (bundled + scanned + user config) for robust stub generation
                val metadata = this.getAllMetadata()
                stubGenerator.generateStubs(metadata, stubsDir)
                classpath.add(stubsDir)
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate Jenkins stubs", e)
        }

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
                val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
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
