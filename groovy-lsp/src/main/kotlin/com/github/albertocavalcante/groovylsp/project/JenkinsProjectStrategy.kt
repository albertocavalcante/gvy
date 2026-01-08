package com.github.albertocavalcante.groovylsp.project

import com.github.albertocavalcante.groovyjenkins.GlobalVariable
import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovyjenkins.JenkinsWorkspaceManager
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.services.JenkinsMetadataService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path

/**
 * Project strategy for Jenkins Pipeline workspaces.
 *
 * Encapsulates all Jenkins-specific logic including:
 * - Plugin management and metadata resolution
 * - Workspace context (Jenkinsfile detection, vars/ globals)
 * - Shared library classpath handling
 * - GDSL metadata loading
 *
 * Implements [JenkinsCapabilities] for type-safe consumer access. Consumers should
 * depend on the capability interface rather than this class directly.
 *
 * ## Initialization
 * Initialization happens in two phases:
 * 1. **Synchronous**: GDSL metadata loading (fast, blocks workspace init)
 * 2. **Asynchronous**: Plugin download and registration (slow, runs in background)
 *
 * Use [awaitInitialization] to wait for async initialization to complete.
 *
 * @param coroutineScope Scope for async operations (plugin downloads)
 */
class JenkinsProjectStrategy(private val coroutineScope: CoroutineScope) :
    ProjectStrategy,
    JenkinsCapabilities {

    private val logger = LoggerFactory.getLogger(JenkinsProjectStrategy::class.java)

    override val id: String = "jenkins"
    override val displayName: String = "Jenkins Pipeline"
    override val priority: Int = 100 // High priority - Jenkins detection before generic strategies

    private var workspaceManager: JenkinsWorkspaceManager? = null
    private var pluginManager: JenkinsPluginManager? = null
    private var initJob: Job? = null

    /**
     * Determines if this strategy can handle the workspace.
     *
     * Returns true if Jenkins file patterns are configured, indicating
     * the workspace may contain Jenkins pipelines.
     */
    override fun canHandle(workspaceRoot: Path, config: ServerConfiguration): Boolean {
        val hasPatterns = config.jenkinsConfig.filePatterns.isNotEmpty()
        if (hasPatterns) {
            logger.debug(
                "Jenkins strategy can handle workspace {} (patterns: {})",
                workspaceRoot,
                config.jenkinsConfig.filePatterns,
            )
        }
        return hasPatterns
    }

    /**
     * Initializes the Jenkins strategy for the workspace.
     *
     * Creates [JenkinsWorkspaceManager] and [JenkinsPluginManager], loads GDSL metadata
     * synchronously, then starts async plugin download.
     *
     * @return Job for async plugin initialization, or null if no plugins configured
     */
    override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? {
        logger.info("Initializing Jenkins strategy for workspace: {}", workspaceRoot)

        // Create plugin manager (shared across workspace)
        val pm = JenkinsPluginManager()
        pluginManager = pm

        // Create workspace manager (owns JenkinsContext)
        val wm = JenkinsWorkspaceManager(config.jenkinsConfig, workspaceRoot, pm)
        workspaceManager = wm

        // Load GDSL metadata synchronously (fast operation)
        runCatching { wm.loadGdslMetadata() }
            .onFailure { e ->
                if (e is CancellationException || e is Error) throw e
                logger.warn("Failed to load GDSL metadata: {}", e.message)
            }

        // Start async plugin download (slow operation)
        val metadataService = JenkinsMetadataService(pm, config.jenkinsConfig)
        initJob = coroutineScope.launch(Dispatchers.IO) {
            runCatching { metadataService.initialize() }
                .onFailure { e ->
                    if (e is CancellationException || e is Error) throw e
                    logger.warn("Failed to initialize Jenkins metadata: {}", e.message)
                }
        }

        logger.info("Jenkins strategy initialized (async plugin download started)")
        return initJob
    }

    // ===== JenkinsCapabilities implementation =====

    override fun isJenkinsFile(uri: URI): Boolean = workspaceManager?.isJenkinsFile(uri) ?: false

    override fun isGdslFile(uri: URI): Boolean = workspaceManager?.isGdslFile(uri) ?: false

    override fun getGlobalVariables(): List<GlobalVariable> = workspaceManager?.getGlobalVariables() ?: emptyList()

    override fun getAllMetadata(): MergedJenkinsMetadata? = workspaceManager?.getAllMetadata()

    override fun reloadGdsl() {
        workspaceManager?.reloadGdslMetadata()
    }

    override suspend fun awaitInitialization() {
        initJob?.join()
    }

    // ===== ProjectStrategy implementation =====

    override fun getSourceRoots(): List<Path> = workspaceManager?.getLibrarySourceRoots() ?: emptyList()

    override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path>? {
        val wm = workspaceManager ?: return null
        // Only return classpath if this is a Jenkins file
        return if (wm.isJenkinsFile(uri)) {
            wm.getClasspathForFile(uri, content, projectDependencies)
        } else {
            null
        }
    }

    override fun updateConfiguration(config: ServerConfiguration) {
        val currentWm = workspaceManager
        if (currentWm != null) {
            workspaceManager = currentWm.updateConfiguration(config.jenkinsConfig)
            logger.info("Updated Jenkins configuration")
        }
    }

    override fun shutdown() {
        logger.info("Shutting down Jenkins strategy")
        initJob?.cancel()
        initJob = null
        workspaceManager = null
        pluginManager = null
    }
}
