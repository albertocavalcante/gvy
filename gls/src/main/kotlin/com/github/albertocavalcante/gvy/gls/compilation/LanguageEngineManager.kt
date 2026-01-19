package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.engine.EngineFactory
import com.github.albertocavalcante.gvy.gls.engine.api.LanguageEngine
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the lifecycle and configuration of the Groovy language engine.
 * Handles engine re-creation on configuration changes and manages associated worker sessions.
 */
data class LanguageEngineManagerOptions(
    val documentProvider: DocumentProvider? = null,
    val sourceNavigator: SourceNavigator? = null,
    val engineConfig: EngineConfiguration = EngineConfiguration(),
)

class LanguageEngineManager(
    private val compilationService: GroovyCompilationService,
    private val parser: GroovyParserFacade,
    private val parentClassLoader: ClassLoader,
    private val workspaceManager: WorkspaceManager,
    options: LanguageEngineManagerOptions = LanguageEngineManagerOptions(),
) {
    private val logger = KotlinLogging.logger {}

    private val documentProvider: DocumentProvider? = options.documentProvider
    private val sourceNavigator: SourceNavigator? = options.sourceNavigator
    private var engineConfig: EngineConfiguration = options.engineConfig

    private val groovyVersionInfo = AtomicReference<GroovyVersionInfo?>(null)
    private val selectedWorker = AtomicReference<WorkerDescriptor?>(null)

    private var activeEngineInstance: LanguageEngine? = null
    private val activeEngineLock = Any()

    private var currentClassLoader: URLClassLoader? = null
    private val classLoaderLock = Any()

    /**
     * The active language engine instance.
     */
    val activeEngine: LanguageEngine
        get() = synchronized(activeEngineLock) {
            activeEngineInstance ?: createEngine().also { activeEngineInstance = it }
        }

    fun updateEngineConfiguration(newConfig: EngineConfiguration) {
        synchronized(activeEngineLock) {
            if (engineConfig != newConfig) {
                logger.info { "Updating engine configuration from ${engineConfig.type.id} to ${newConfig.type.id}" }
                engineConfig = newConfig
                activeEngineInstance = null // Invalidate to force re-creation
            }
        }
    }

    private fun createEngine(): LanguageEngine {
        val config = engineConfig
        logger.info { "Creating language engine: ${config.type.id}" }
        return EngineFactory.create(
            config = config,
            parser = parser,
            compilationService = compilationService,
            documentProvider = documentProvider ?: error("DocumentProvider required for engine"),
            sourceNavigator = sourceNavigator,
        )
    }

    fun updateGroovyVersion(info: GroovyVersionInfo) {
        groovyVersionInfo.set(info)
    }

    fun getGroovyVersionInfo(): GroovyVersionInfo? = groovyVersionInfo.get()

    fun updateSelectedWorker(worker: WorkerDescriptor?) {
        selectedWorker.set(worker)
    }

    fun getSelectedWorker(): WorkerDescriptor? = selectedWorker.get()

    fun getOrCreateClassLoader(): ClassLoader {
        synchronized(classLoaderLock) {
            currentClassLoader?.let { return it }

            val classpath = workspaceManager.getDependencyClasspath()
            if (classpath.isEmpty()) {
                return parentClassLoader
            }

            logger.info { "Creating URLClassLoader with ${classpath.size} dependencies" }
            val urls = classpath.map { it.toUri().toURL() }.toTypedArray()
            return URLClassLoader(urls, parentClassLoader).also {
                currentClassLoader = it
            }
        }
    }

    fun invalidateClassLoader() {
        synchronized(classLoaderLock) {
            currentClassLoader?.let {
                logger.info { "Invalidating ClassLoader" }
                runCatching { it.close() }
                    .onFailure { throwable ->
                        rethrowIfCancellationOrError(throwable)
                        logger.warn(throwable) { "Failed to close ClassLoader" }
                    }
                currentClassLoader = null
            }
        }
    }

    private fun rethrowIfCancellationOrError(throwable: Throwable) {
        when (throwable) {
            is CancellationException -> throw throwable
            is Error -> throw throwable
        }
    }
}
