// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class ConfigurationTransportTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val compilation = GroovyCompilationService()
    private val service = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, scope)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `JSON RPC settings changes resize the cache without restarting`() {
        assertEquals(300, compilation.getCacheStatistics()["maxEntries"])

        notifySettings("""{"groovy.compilation.cacheMaxEntries":42}""")
        assertEquals(42, compilation.getCacheStatistics()["maxEntries"])

        notifySettings(
            """{"groovy.compilation.cacheMaxEntries":17}""",
            """{"groovy.compilation.cacheMaxEntries":12}""",
        )
        assertEquals(12, compilation.getCacheStatistics()["maxEntries"])
    }

    @Test
    fun `direct map settings remain supported`() {
        service.didChangeConfiguration(DidChangeConfigurationParams(mapOf("groovy.compilation.cacheMaxEntries" to 42)))
        assertEquals(42, compilation.getCacheStatistics()["maxEntries"])
    }

    @Test
    fun `JSON cache limits retain the minimum of one`() {
        for (limit in listOf(0, -3)) {
            notifySettings("""{"groovy.compilation.cacheMaxEntries":$limit}""")
            assertEquals(1, compilation.getCacheStatistics()["maxEntries"])
        }
    }

    @Test
    fun `an empty settings object retains the complete configuration defaults contract`() {
        compilation.updateCompilationCacheLimit(42)
        notifySettings("{}")
        assertEquals(300, compilation.getCacheStatistics()["maxEntries"])
    }

    @Test
    fun `non-object settings do not reset an existing cache limit`() {
        compilation.updateCompilationCacheLimit(42)

        for (settings in listOf("null", "[]", "17", "\"invalid\"")) {
            notifySettings(settings)
            assertEquals(42, compilation.getCacheStatistics()["maxEntries"], "settings=$settings")
        }
    }

    private fun notifySettings(vararg settings: String) {
        val messages = settings.joinToString("") { value ->
            val notification = """
                {"jsonrpc":"2.0","method":"workspace/didChangeConfiguration","params":{"settings":$value}}
            """.trimIndent()
            "Content-Length: ${notification.toByteArray(Charsets.UTF_8).size}\r\n\r\n$notification"
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            ByteArrayInputStream(messages.toByteArray(Charsets.UTF_8)).use { input ->
                ByteArrayOutputStream().use { output ->
                    val launcher = Launcher.Builder<LanguageClient>()
                        .setLocalService(service)
                        .setRemoteInterface(LanguageClient::class.java)
                        .setInput(input)
                        .setOutput(output)
                        .setExecutorService(executor)
                        .create()
                    launcher.startListening().get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
