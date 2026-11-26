// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.providers.testing.TestDiscoveryProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

class DiscoveryReuseTest {
    @Test
    fun `a legacy compile of supplied text cannot replace the closed file in public discovery`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val disk = "class Example { @org.junit.jupiter.api.Test void diskTest() {} }"
        val uri = Files.writeString(root.resolve("Subject.groovy"), disk).toUri()
        val supplied = disk.replace("diskTest", "suppliedTest")
        val compilation = GroovyCompilationService(DiscoveryReuseTest::class.java.classLoader)
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.compile(uri, supplied)
            assertEquals(supplied, compilation.getCompiledContent(uri))
            val detectors = TestFrameworkRegistry().apply { registerIfAbsent(JUnit5TestDetector()) }
            val discovery = TestDiscoveryProvider(compilation, detectors)
            val suite = discovery.discoverTests(root.toUri().toString()).single()
            assertEquals("diskTest", suite.tests.single().test)
        } finally {
            compilation.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `repeated discovery reuses installed symbols and the same corrected parse until the source changes`(
        scriptFallback: Boolean,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val conversions = AtomicInteger()
        val builds = AtomicInteger()
        val source = "class Example { @org.junit.jupiter.api.Test void firstTest() {} }"
        val path = Files.writeString(root.resolve("Subject.groovy"), source)
        val compilation = discoveryService(scriptFallback) { request, parsed ->
            if (request.compilePhase == Phases.CONVERSION) conversions.incrementAndGet()
            observeSymbolBuild(parsed) { builds.incrementAndGet() }
        }
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            val detectors = TestFrameworkRegistry().apply { registerIfAbsent(JUnit5TestDetector()) }
            val discovery = TestDiscoveryProvider(compilation, detectors)
            val workspace = root.toUri().toString()
            val first = discovery.discoverTests(workspace).single()
            assertEquals("firstTest", first.tests.single().test)
            assertEquals(1, builds.get())
            val expectedConversions = if (scriptFallback) 1 else 0
            assertEquals(expectedConversions, conversions.get())

            assertEquals(first, discovery.discoverTests(workspace).single())
            assertAll(
                { assertEquals(expectedConversions, conversions.get(), "Unchanged recovery should be reused") },
                { assertEquals(1, builds.get(), "Unchanged installed symbols should be reused") },
            )

            Files.writeString(path, source.replace("firstTest", "secondTest"))
            compilation.onClosedSourceChanged(path.toUri(), keepIfUnchanged = false)
            val changed = discovery.discoverTests(workspace).single()
            assertEquals("secondTest", changed.tests.single().test)
            assertEquals(2, builds.get())
            assertEquals(2 * expectedConversions, conversions.get())
        } finally {
            compilation.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["fatal", "cancelled", "io"])
    fun `an unpublished recovery closes once and preserves the original failure`(
        kind: String,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val primary = when (kind) {
            "fatal" -> AssertionError("symbol model failed")
            "cancelled" -> CancellationException("symbol model cancelled")
            else -> IOException("symbol model unavailable")
        }
        val cleanup = IOException("discovery loader close failed")
        val closes = AtomicInteger()
        Files.writeString(root.resolve("Subject.groovy"), "class Example {}")
        val compilation = discoveryService(scriptFallback = true) { request, parsed ->
            if (request.compilePhase == Phases.CONVERSION) {
                val loader = mockk<GroovyClassLoader>()
                every { loader.close() } answers {
                    closes.incrementAndGet()
                    parsed.compilationUnit.classLoader.close()
                    throw cleanup
                }
                val owned = mockk<CompilationUnit> { every { classLoader } returns loader }
                observeSymbolBuild(parsed.copy(compilationUnit = owned)) { throw primary }
            } else {
                parsed
            }
        }
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            val discovery = TestDiscoveryProvider(compilation, TestFrameworkRegistry())
            val observed = assertFails { discovery.discoverTests(root.toUri().toString()) }
            assertEquals(1, closes.get())
            assertSame(primary, observed)
            assertEquals(listOf(cleanup), observed.suppressed.toList())
        } finally {
            compilation.shutdown()
        }
    }

    private fun discoveryService(scriptFallback: Boolean, inspect: (ParseRequest, ParseResult) -> ParseResult) =
        GroovyCompilationService(
            DiscoveryReuseTest::class.java.classLoader,
            workerSessionDecorator = { worker ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val input = if (scriptFallback && request.compilePhase == Phases.CANONICALIZATION) {
                            request.copy(content = "def placeholder() {}")
                        } else {
                            request
                        }
                        return inspect(request, worker.parse(input))
                    }
                }
            },
        )

    private fun observeSymbolBuild(parsed: ParseResult, beforeBuild: () -> Unit): ParseResult {
        val model = parsed.astModel
        return parsed.copy(
            astModel = object : GroovyAstModel by model {
                override fun getAllClassNodes(): List<ClassNode> {
                    beforeBuild()
                    return model.getAllClassNodes()
                }
            },
        )
    }
}
