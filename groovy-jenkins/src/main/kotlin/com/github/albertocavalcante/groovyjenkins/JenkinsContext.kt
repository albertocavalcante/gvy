package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovygdsl.GdslExecutor
import com.github.albertocavalcante.groovygdsl.GdslLoadResults
import com.github.albertocavalcante.groovygdsl.GdslLoader
import com.github.albertocavalcante.groovyjenkins.plugins.PluginDiscoveryService
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

        return classpath
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

        results.failed.forEach { result ->
            logger.warn("Failed to load Jenkins GDSL: ${result.path} - ${result.error}")
        }

        return results
    }

    /**
     * Checks if a URI is a Jenkins pipeline file based on configured patterns.
     */
    fun isJenkinsFile(uri: java.net.URI): Boolean = fileDetector.isJenkinsFile(uri)

    private val scanner = com.github.albertocavalcante.groovyjenkins.scanning.JenkinsClasspathScanner()
    private val dynamicMetadataCache =
        mutableMapOf<Int, com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata>()
    private var currentClasspathHash: Int = 0

    /**
     * Get combined metadata (bundled + dynamic), optionally filtered by installed plugins.
     *
     * HEURISTIC: If plugins.txt exists, filter to only show steps from installed plugins.
     * If no plugins.txt, show all bundled metadata (better UX for users without JCasC).
     */
    fun getAllMetadata(): com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata {
        val bundled = com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadataLoader().load()
        val dynamic = dynamicMetadataCache[currentClasspathHash]

        val merged = if (dynamic != null) {
            // Merge logic: dynamic overrides bundled for ALL metadata types
            com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata(
                steps = bundled.steps + dynamic.steps,
                globalVariables = bundled.globalVariables + dynamic.globalVariables,
                postConditions = bundled.postConditions + dynamic.postConditions,
                declarativeOptions = bundled.declarativeOptions + dynamic.declarativeOptions,
                agentTypes = bundled.agentTypes + dynamic.agentTypes,
            )
        } else {
            bundled
        }

        // Apply plugin filtering ONLY if explicit configuration exists (not just defaults)
        // HEURISTIC: If user hasn't configured plugins.txt or jenkins.plugins, show all bundled
        if (!pluginDiscovery.hasPluginConfiguration()) {
            return merged
        }

        // Filter metadata to only installed plugins (including defaults if enabled)
        val installedPlugins = pluginDiscovery.getInstalledPluginNames()
        logger.debug("Filtering metadata to {} installed plugins", installedPlugins.size)
        return merged.copy(
            steps = merged.steps.filterValues { it.plugin in installedPlugins },
            declarativeOptions = merged.declarativeOptions.filterValues { it.plugin in installedPlugins },
        )
    }

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
