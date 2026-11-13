// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class HeldAdmissionFixture(root: Path, returnUncachedFailure: Boolean, openPrimary: Boolean = true) :
    AutoCloseable {
    private val ordinary = Files.createDirectory(root.resolve("ordinary"))
    val managed: Path = Files.createDirectory(root.resolve("managed"))
    private val sources = Files.createDirectory(root.resolve("src"))
    private val declaration = "package p\n@interface Mode {}"
    init {
        Files.writeString(Files.createDirectories(ordinary.resolve("p")).resolve("Mode.groovy"), declaration)
        Files.writeString(Files.createDirectories(managed.resolve("p")).resolve("Mode.groovy"), declaration)
    }
    val alias: Path = Files.createSymbolicLink(root.resolve("dependency"), ordinary)
    val content = "import p.Mode\n@Mode class Money {}"
    val uri = Files.writeString(sources.resolve("Money.groovy"), content).toUri()
    val documents = DocumentProvider().apply {
        if (openPrimary) put(uri, content, 1)
    }
    private val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
    private val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) }).apply {
        updateWorkspaceModel(root, emptyList(), listOf(alias, sources))
    }
    private val loaders = PublishedParserLoaders()
    val producer = HeldAdmissionProducer(returnUncachedFailure, loaders)
    val compilation = GroovyCompilationService(
        documentProvider = documents,
        workspaceManager = manager,
        workerSessionDecorator = producer::decorate,
    )

    override fun close() {
        producer.release.countDown()
        compilation.shutdown()
        loaders.close()
    }
}

internal class HeldAdmissionProducer(
    private val returnUncachedFailure: Boolean,
    private val loaders: PublishedParserLoaders,
) {
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val selectedSource = AtomicReference<String?>()
    val parses = AtomicInteger()
    private val gateFirst = AtomicBoolean(true)

    fun assertOutcome(result: CompilationResult, retarget: Boolean) {
        if (retarget) {
            assertNull(
                result.sourceAnnotations.headAt(1, 1)?.sourceUnitName,
                "An awaited result must not expose a binding from before the admission change",
            )
            assertNotNull(result.ast, "Re-resolved compilation must provide the current AST")
            assertTrue(parses.get() >= 2, "Changed producer admission requires a new compilation")
        } else {
            assertFalse(result.isSuccess)
            assertNull(result.ast)
            assertEquals(selectedSource.get(), result.sourceAnnotations.headAt(1, 1)?.sourceUnitName)
            assertEquals(1, parses.get(), "An unchanged uncached failure must not retry forever")
        }
    }

    fun decorate(delegate: WorkerSession): WorkerSession = object : WorkerSession {
        override fun parse(request: ParseRequest): ParseResult {
            parses.incrementAndGet()
            val parsed = delegate.parse(request)
            loaders.record(parsed.compilationUnit.classLoader)
            if (gateFirst.compareAndSet(true, false)) {
                selectedSource.set(parsed.sourceAnnotations.headAt(1, 1)?.sourceUnitName)
                entered.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS)) { "The test must release its parser barrier" }
                if (returnUncachedFailure) return parsed.copy(ast = null)
            }
            return parsed
        }
    }
}

internal class PublishedParserLoaders : AutoCloseable {
    private val monitor = Any()
    private val loaders = linkedSetOf<GroovyClassLoader>()
    private var closed = false

    fun record(loader: GroovyClassLoader) {
        val closeImmediately = synchronized(monitor) {
            if (closed) {
                true
            } else {
                loaders.add(loader)
                false
            }
        }
        if (closeImmediately) loader.close()
    }

    override fun close() {
        val remaining = synchronized(monitor) {
            closed = true
            val snapshot = loaders.toList()
            loaders.clear()
            snapshot
        }
        remaining.forEach(GroovyClassLoader::close)
    }
}
