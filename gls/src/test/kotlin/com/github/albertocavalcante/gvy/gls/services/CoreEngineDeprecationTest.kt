// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreEngineDeprecationTest {
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val scopes = mutableListOf<CoroutineScope>()
    private val compilation = mockk<GroovyCompilationService>(relaxed = true)
    private var savedLevels: Map<String, Level?> = emptyMap()

    @BeforeEach
    fun captureLogs() {
        savedLevels = listOf(Logger.ROOT_LOGGER_NAME, "com.github.albertocavalcante.groovylsp", "GroovyLSP")
            .associateWith { context.getLogger(it).level }
        root.addAppender(logs)
    }

    @AfterEach
    fun releaseLogsAndScopes() {
        root.detachAppender(logs)
        logs.stop()
        scopes.forEach { it.cancel() }
        savedLevels.forEach { (name, level) -> context.getLogger(name).level = level }
    }

    @Test
    fun `legacy parsing is pure and does not consume a server warning`() {
        EngineType.fromString("core")
        ServerConfiguration.fromMap(settings("CORE"))

        assertTrue(deprecations().isEmpty())
    }

    @Test
    fun `one manager warns once for repeated legacy settings and names the exact cutoff`() {
        val manager = manager()
        start(manager, "core")
        start(manager, "CORE")

        assertEquals(1, deprecations().size, "A manager must report one migration warning")
        val message = deprecations().single().formattedMessage
        assertPolicy(message)
        verify(exactly = 2) { compilation.updateEngineConfiguration(EngineConfiguration(type = EngineType.Native)) }
    }

    @Test
    fun `a later legacy setting warns without Native or OpenRewrite consuming the warning`() {
        val manager = manager()
        start(manager, "native")
        start(manager, "openrewrite")
        assertTrue(deprecations().isEmpty())

        start(manager, "core")
        start(manager, "native")
        start(manager, "core")

        assertEquals(1, deprecations().size)
        assertPolicy(deprecations().single().formattedMessage)
    }

    @Test
    fun `each manager reports its own migration warning`() {
        start(manager(), "core")
        start(manager(), "core")

        assertEquals(2, deprecations().size)
        deprecations().forEach { assertPolicy(it.formattedMessage) }
    }

    @Test
    fun `public live configuration intake reports a saved Core setting once`() {
        withServer("native") { server ->
            assertTrue(deprecations().isEmpty())

            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings("core")))
            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings("CORE")))

            assertEquals(1, deprecations().size)
            assertPolicy(deprecations().single().formattedMessage)
        }
    }

    @Test
    fun `startup and live configuration share one server migration warning`() {
        withServer("core") { server ->
            assertEquals(1, deprecations().size)

            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings("CORE")))

            assertEquals(1, deprecations().size)
            assertPolicy(deprecations().single().formattedMessage)
        }
    }

    private fun manager(): ProjectStartupManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return ProjectStartupManager(compilation, emptyList(), scope, ProjectStrategyRegistry())
    }

    private fun start(manager: ProjectStartupManager, engine: String) {
        manager.startAsyncDependencyResolution(
            client = null,
            initParams = null,
            initOptionsMap = settings(engine),
            textDocumentServiceRefresh = {},
        )
    }

    private fun settings(engine: String): Map<String, Any> = mapOf("groovy.languageServer.engine" to engine)

    private fun withServer(engine: String, action: (GroovyLanguageServer) -> Unit) {
        val server = GroovyLanguageServer()
        try {
            server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    initializationOptions = settings(engine)
                },
            ).get(SERVER_LIFECYCLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            action(server)
        } finally {
            server.shutdown().get(SERVER_LIFECYCLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun deprecations(): List<ILoggingEvent> = logs.list.filter {
        it.level == Level.WARN &&
            it.formattedMessage.contains("groovy.languageServer.engine") &&
            it.formattedMessage.contains("deprecat", ignoreCase = true)
    }

    private fun assertPolicy(message: String) {
        assertContains(message, "core", ignoreCase = true)
        assertContains(message, "native", ignoreCase = true)
        assertContains(message, "through 0.5.5")
        assertContains(message, "removed in 0.5.6")
    }

    private companion object {
        const val SERVER_LIFECYCLE_TIMEOUT_SECONDS = 30L
    }
}
