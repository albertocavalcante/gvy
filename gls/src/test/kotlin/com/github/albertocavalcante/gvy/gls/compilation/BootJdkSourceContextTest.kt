// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.sources.SourceOrigin
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.NativeClasspathInputs
import com.github.albertocavalcante.nativeapi.NativeJdkIdentity
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.transform.CompileStatic
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootJdkSourceContextTest {
    @TempDir
    lateinit var root: Path

    private val uri = URI.create("file:///source-context/Money.groovy")
    private val content = "class Money { String currency }"

    @Test
    fun `source contexts reject a prior boot home version vendor or module stamp without changing parsing`(): Unit =
        runBlocking {
            val variants: List<(NativeJdkIdentity) -> NativeJdkIdentity> = listOf(
                { it.copy(imageHome = root.resolve("previous-image")) },
                { it.copy(runtimeVersion = it.runtimeVersion + "-previous") },
                { it.copy(vendor = it.vendor + " previous") },
                { it.copy(modulesStamp = it.modulesStamp.copy(size = it.modulesStamp.size + 1)) },
            )
            for (previous in variants) {
                val compilation = GroovyCompilationService(
                    parentClassLoader = CompileStatic::class.java.classLoader,
                    workerSessionDecorator = { delegate ->
                        object : WorkerSession {
                            override fun parse(request: ParseRequest): ParseResult {
                                val parsed = delegate.parse(request)
                                val inputs = assertNotNull(parsed.classpathInputs)
                                return parsed.copy(
                                    classpathInputs = NativeClasspathInputs(
                                        inputs.classpath,
                                        inputs.sourceRoots,
                                        inputs.classpathStamps,
                                        inputs.compilerSdk,
                                        previous(assertNotNull(inputs.bootJdk)),
                                    ),
                                )
                            }
                        }
                    },
                )
                try {
                    assertTrue(compilation.compile(uri, content).isSuccess)
                    assertNull(
                        compilation.captureSourceRequest(uri, "java.lang.String"),
                        "A different boot image cannot certify the held parse",
                    )
                } finally {
                    compilation.shutdown()
                }
            }
        }

    @Test
    fun `JRT source origins use the captured image and cannot rebind a descriptor from another image`(): Unit =
        runBlocking {
            val compilation = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
            try {
                assertTrue(compilation.compile(uri, content).isSuccess)
                val snapshot = assertNotNull(compilation.getCompiledSnapshot(uri))
                val boot = assertNotNull(snapshot.parseResult.classpathInputs?.bootJdk)
                val request = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
                val origin = assertIs<SourceOrigin.Jdk>(request.symbol.origin)
                assertEquals(boot.imageHome, origin.imageHome)
                val previousView = SourceDocumentDescriptor(
                    "a".repeat(64),
                    "b".repeat(64),
                    "java/lang/String.java",
                    root.resolve("String.java"),
                    origin.copy(imageHome = root.resolve("previous-image")),
                    request.context.identity,
                    "JDK",
                    "java",
                )

                assertNull(compilation.captureSourceLookupSession(previousView))
            } finally {
                compilation.shutdown()
            }
        }

    @Test
    fun `recovery to conversion cannot certify binary origin merely by retaining the semantic request inputs`(): Unit =
        runBlocking {
            val compilation = GroovyCompilationService(
                parentClassLoader = CompileStatic::class.java.classLoader,
                workerSessionDecorator = { delegate ->
                    object : WorkerSession {
                        override fun parse(request: ParseRequest): ParseResult {
                            val parsed = delegate.parse(request)
                            return parsed.copy(outcome = parsed.outcome.copy(achievedPhase = Phases.CONVERSION))
                        }
                    }
                },
            )
            try {
                assertTrue(compilation.compile(uri, content).isSuccess)
                assertNotNull(compilation.getCompiledSnapshot(uri)?.parseResult?.classpathInputs?.bootJdk)
                assertNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            } finally {
                compilation.shutdown()
            }
        }
}
