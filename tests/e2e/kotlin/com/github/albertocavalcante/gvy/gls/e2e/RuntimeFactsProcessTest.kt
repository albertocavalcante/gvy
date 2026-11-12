// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeFactsProcessTest {
    @Test
    fun `runtime facts identify the actual server subprocess without starting the hover engine`() {
        LanguageServerSessionFactory().start(ServerConfig(), "runtime-facts-process").use { session ->
            val process = requireNotNull(session.process)
            val jar = requireNotNull(
                BazelRunfiles.resolve(requireNotNull(System.getProperty("groovy.lsp.e2e.execJar"))),
            ) { "Configured server JAR could not be resolved" }.toRealPath()
            val args = process.info().arguments().orElseThrow().toList()
            val jarIndex = args.indexOf("-jar")
            assertTrue(jarIndex >= 0 && jarIndex + 1 < args.size)
            assertEquals(jar, Path.of(args[jarIndex + 1]).toRealPath())
            session.server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val result = session.groovyServer.runtimeFacts().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).asJsonObject
            val runtime = result.getAsJsonObject("runtime")
            assertEquals("known", runtime.get("processState").asString)
            assertEquals(process.pid(), runtime.get("processId").asLong)
            assertEquals("known", runtime.get("javaState").asString)
            assertEquals(javaRuntimeVersion(process), runtime.get("javaVersion").asString)
            assertEquals("known", runtime.get("heapState").asString)
            assertTrue(runtime.get("maxHeapBytes").asLong in 1..MAX_SERVER_HEAP_BYTES)
            val hover = result.getAsJsonObject("hoverEngine")
            assertEquals("not_instantiated", hover.get("state").asString)
            assertFalse(hover.has("instantiated"))

            session.server.initialized(InitializedParams())
            session.client.readyFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val configured = session.groovyServer.runtimeFacts().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .asJsonObject.getAsJsonObject("hoverEngine").get("configured").asString
            val expectedInstance = when (configured) {
                "core", "native" -> configured
                "openrewrite" -> "rewrite"
                else -> error("Unexpected configured hover engine")
            }
            val uri = "file:///runtime-facts/Probe.groovy"
            session.server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "def value = 'hello'\nvalue")),
            )
            session.server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(1, 2)))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val afterHover = session.groovyServer.runtimeFacts().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .asJsonObject.getAsJsonObject("hoverEngine")
            assertEquals("known", afterHover.get("state").asString)
            assertEquals(expectedInstance, afterHover.get("instantiated").asString)

            session.server.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.exit()
        }
    }

    private fun javaRuntimeVersion(server: Process): String {
        val executable = server.info().command().orElseThrow()
        val reader = Executors.newSingleThreadExecutor()
        var probe: Process? = null
        var primary: Throwable? = null
        var interrupted = false
        try {
            val child = ProcessBuilder(executable, "-Xmx64m", "-XshowSettings:properties", "-version")
                .redirectErrorStream(true)
                .start()
            probe = child
            val output = reader.submit<ByteArray> {
                child.inputStream.use { it.readNBytes(MAX_PROBE_BYTES + 1) }
            }
            assertTrue(child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            val bytes = output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(bytes.size <= MAX_PROBE_BYTES)
            return bytes.toString(Charsets.UTF_8).lineSequence().map(String::trim)
                .first { it.startsWith("java.runtime.version = ") }
                .substringAfter(" = ")
        } catch (failure: Throwable) {
            interrupted = failure is InterruptedException
            primary = failure
            throw failure
        } finally {
            var cleanup: Throwable? = null
            try {
                val child = probe
                if (child != null && child.isAlive) {
                    child.destroyForcibly()
                    check(child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Java version probe did not terminate" }
                }
            } catch (failure: Throwable) {
                interrupted = interrupted || failure is InterruptedException
                cleanup = failure
            } finally {
                try {
                    reader.shutdownNow()
                    check(reader.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "Java version reader did not terminate"
                    }
                } catch (failure: Throwable) {
                    interrupted = interrupted || failure is InterruptedException
                    if (cleanup == null) {
                        cleanup = failure
                    } else if (cleanup !== failure) {
                        cleanup.addSuppressed(failure)
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            cleanup?.let { failure ->
                val original = primary
                if (original == null) throw failure
                if (original !== failure) original.addSuppressed(failure)
            }
        }
    }

    private companion object {
        const val MAX_PROBE_BYTES = 65_536
        const val TIMEOUT_SECONDS = 30L
        const val MAX_SERVER_HEAP_BYTES = 1_073_741_824L
    }
}
