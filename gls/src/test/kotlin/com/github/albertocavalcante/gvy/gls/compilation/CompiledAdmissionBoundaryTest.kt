// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompiledAdmissionBoundaryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `raw cache publication cannot certify a source request without a receipt`(): Unit = runBlocking {
        val uri = root.resolve("Money.groovy").toUri()
        val source = "class Money {}"
        Fixture(WorkspaceManager()).use { fixture ->
            val service = fixture.service
            assertTrue(service.compile(uri, source).isSuccess)
            val before = assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})
            val cache = service.compilationCacheService
            assertTrue(
                cache.publish(
                    cache.ownership.begin(uri),
                    source,
                    before.parseResult,
                    assertNotNull(before.configurationFingerprint),
                ),
            )

            assertSame(before.parseResult, service.getParseResult(uri), "Structural reads remain available")
            assertEquals(source, service.getCompiledContent(uri))
            assertNull(service.getAdmittedCompiledSnapshot(uri) {})
            assertFalse(service.isAdmittedCompiledSnapshotCurrent(uri, before) {})
        }
    }

    @Test
    fun `raw text and record readers do not perform admission inside a publication fence`(): Unit = runBlocking {
        var insideFence = false
        val manager = WorkspaceManager(sourceExclusion = {
            check(!insideFence) { "Raw cache reads must not perform filesystem admission under a fence" }
            false
        })
        val uri = root.resolve("Money.groovy").toUri()
        val source = "class Money {}"
        Fixture(manager).use { fixture ->
            val service = fixture.service
            assertTrue(service.compile(uri, source).isSuccess)
            val held = assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})
            val cache = service.compilationCacheService
            val fingerprint = assertNotNull(held.configurationFingerprint)
            assertTrue(
                cache.publish(
                    cache.ownership.begin(uri),
                    source,
                    held.parseResult,
                    fingerprint,
                    isValid = {
                        insideFence = true
                        try {
                            assertEquals(source, service.getCompiledContent(uri))
                            assertSame(held.parseResult, service.getParseResult(uri))
                            assertEquals(
                                held.admissionFingerprint,
                                cache.getCachedRecord(uri, fingerprint)?.admissionFingerprint,
                            )
                            true
                        } finally {
                            insideFence = false
                        }
                    },
                    admissionFingerprint = held.admissionFingerprint,
                ),
            )
        }
    }

    @Test
    fun `source capture and final session validation observe secondary admission with unchanged roots`(): Unit =
        runBlocking {
            assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
            val ordinary = Files.createDirectory(root.resolve("ordinary"))
            val managed = Files.createDirectory(root.resolve("managed"))
            val sources = Files.createDirectory(root.resolve("src"))
            val originalFile = Files.writeString(ordinary.resolve("Other.groovy"), "class Other {}")
            val managedFile = Files.writeString(managed.resolve("Other.groovy"), "class Other {}")
            val alias = Files.createSymbolicLink(sources.resolve("Extra.groovy"), originalFile)
            val source = "class Money { String currency }"
            val uri = Files.writeString(sources.resolve("Money.groovy"), source).toUri()
            val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
            val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
            manager.updateWorkspaceModel(root, emptyList(), listOf(sources))
            val model = manager.current()
            Fixture(manager).use { fixture ->
                val service = fixture.service
                assertTrue(service.compile(uri, source).isSuccess)
                val snapshot = assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})
                assertTrue(service.isAdmittedCompiledSnapshotCurrent(uri, snapshot) {})
                val request = assertNotNull(service.captureSourceRequest(uri, "java.lang.String"))
                val view = SourceDocumentDescriptor(
                    "a".repeat(64),
                    "b".repeat(64),
                    "java/lang/String.java",
                    root.resolve("String.java"),
                    request.symbol.origin,
                    request.context.identity,
                    "JDK",
                    "java",
                )
                val session = assertNotNull(service.captureSourceLookupSession(view))

                Files.delete(alias)
                Files.createSymbolicLink(alias, managedFile)

                assertSame(model, manager.current())
                assertFalse(service.isAdmittedCompiledSnapshotCurrent(uri, snapshot) {})
                assertNull(service.getAdmittedCompiledSnapshot(uri) {})
                assertNull(service.captureSourceRequest(uri, "java.lang.String"))
                assertFalse(request.context.isCurrent())
                assertFalse(session.validateCurrent())
                assertEquals(source, service.getCompiledContent(uri), "Raw positional text is not freshness authority")

                assertTrue(service.compile(uri, source).isSuccess)
                val filtered = assertNotNull(service.captureSourceRequest(uri, "java.lang.String"))
                assertNotEquals(request.context.identity, filtered.context.identity)
                assertNull(service.captureSourceLookupSession(view), "Old source views cannot adopt the new admission")
            }
        }

    private class Fixture(manager: WorkspaceManager) : AutoCloseable {
        private val loaders = linkedSetOf<GroovyClassLoader>()
        val service = GroovyCompilationService(
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        loaders.add(parsed.compilationUnit.classLoader)
                        return parsed
                    }
                }
            },
        )

        override fun close() {
            service.shutdown()
            loaders.forEach(GroovyClassLoader::close)
        }
    }
}
