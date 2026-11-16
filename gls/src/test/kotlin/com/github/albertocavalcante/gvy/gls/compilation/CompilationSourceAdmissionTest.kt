// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilationSourceAdmissionTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `managed source presentation URIs cannot enter workspace compilation`() = runBlocking {
        val uri = URI.create("gvy-source://context/archive/Money.java")
        val parses = AtomicInteger()
        val compilation = GroovyCompilationService(
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        parses.incrementAndGet()
                        return delegate.parse(request)
                    }
                }
            },
        )

        try {
            assertFalse(compilation.compile(uri, "class Money {}").isSuccess)
            assertEquals(0, parses.get())
            assertNull(compilation.getCompiledSnapshot(uri))
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `implicit source root lookup rejects managed sources below an ordinary root`() = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managedPackage = Files.createDirectories(root.resolve("managed/p"))
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val managedFile = Files.writeString(managedPackage.resolve("Mode.groovy"), "package p\n@interface Mode {}")
        Files.createSymbolicLink(sourceRoot.resolve("p"), managedPackage)
        val content = "import p.Mode\n@Mode class Money {}"
        val uri = Files.writeString(sourceRoot.resolve("Money.groovy"), content).toUri()
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(root.resolve("managed")))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(sourceRoot))
        val compilation = GroovyCompilationService(workspaceManager = manager)

        try {
            compilation.compile(uri, content)
            val parsed = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertEquals(listOf(sourceRoot), assertNotNull(parsed.classpathInputs).sourceRoots)
            val sourceUri = parsed.sourceAnnotations.headAt(1, 1)?.sourceUri
            assertFalse(
                sourceUri?.let { Path.of(it).toRealPath() } == managedFile.toRealPath(),
                "The source-root class loader must not bind the managed annotation declaration",
            )
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `editing an admitted open secondary buffer invalidates the primary compilation cache`() = runBlocking {
        val primary = Files.writeString(root.resolve("Primary.groovy"), "class Primary { Secondary value }").toUri()
        val secondary = root.resolve("Secondary.groovy").toUri()
        val first = "class Secondary { int first }"
        val second = "class Secondary { int second }"
        val documents = DocumentProvider().apply { put(secondary, first, 1) }
        val parses = AtomicInteger()
        val openSourceContents = mutableListOf<String?>()
        val loaders = PublishedParserLoaders()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = WorkspaceManager(),
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        parses.incrementAndGet()
                        openSourceContents += request.openSources[secondary]
                        val parsed = delegate.parse(request)
                        loaders.record(parsed.compilationUnit.classLoader)
                        return parsed
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(primary, "class Primary { Secondary value }").isSuccess)
            assertEquals(1, parses.get())
            assertEquals(first, openSourceContents.last())

            assertNotNull(documents.update(secondary, second, 2))
            assertTrue(compilation.compile(primary, "class Primary { Secondary value }").isSuccess)
            assertEquals(2, parses.get(), "A changed secondary buffer must not reuse the old primary parse")
            assertEquals(second, openSourceContents.last())
        } finally {
            compilation.shutdown()
            loaders.close()
        }
    }

    @Test
    fun `retargeted roots, buffers, and class-locator results are excluded before parsing`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        val sources = Files.createDirectory(root.resolve("src"))
        Files.writeString(ordinary.resolve("Mode.groovy"), "package p\nclass Mode {}")
        Files.writeString(managed.resolve("Mode.groovy"), "package p\nclass Mode {}")
        val alias = Files.createSymbolicLink(root.resolve("dependency"), ordinary)
        val selected = alias.resolve("Mode.groovy").toUri()
        val uri = sources.resolve("Money.groovy").toUri()
        val unsaved = sources.resolve("Unsaved.groovy").toUri()
        val documents = DocumentProvider().apply {
            put(selected, "package p\nclass Mode {}", 1)
            put(unsaved, "class Unsaved {}", 1)
        }
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(alias, sources))
        val requests = mutableListOf<ParseRequest>()
        val located = mutableListOf<Path>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        requests.add(request)
                        located.addAll(request.workspaceClassLocator?.invoke("p.Mode").orEmpty())
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(selected, "package p\nclass Mode {}").isSuccess)
            assertNotNull(compilation.symbolIndexingService.findClasses(ClassNameQuery.Exact("Mode")).singleOrNull())
            Files.delete(alias)
            Files.createSymbolicLink(alias, managed)
            requests.clear()
            located.clear()

            assertTrue(compilation.compile(uri, "class Money {}").isSuccess)
            compilation.compileTransient(uri, "class Money { String currency }")
            val disk = sources.resolve("Disk.groovy")
            Files.writeString(disk, "class Disk {}")
            assertNotNull(compilation.ensureCompiled(disk.toUri()))
            compilation.symbolIndexingService.invalidate(disk.toUri())
            assertNotNull(compilation.symbolIndexingService.indexFile(disk.toUri()))

            assertTrue(
                requests.size >= 4,
                "Normal, transient, minimal and indexing paths must all reach the admitted worker",
            )
            assertRetargetedInputsExcluded(requests, alias, selected, uri, unsaved)
            assertTrue(located.isEmpty(), "An old indexed URI cannot reintroduce the retargeted managed class")
            assertNotNull(
                compilation.captureSourceRequest(uri, "java.lang.String"),
                "Filtered native roots still form a usable ordinary source context",
            )
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `closed cached documents recompile without a secondary alias that became managed`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        val sources = Files.createDirectory(root.resolve("src"))
        Files.writeString(ordinary.resolve("Mode.groovy"), "package p\nclass Mode {}")
        Files.writeString(managed.resolve("Mode.groovy"), "package p\nclass Mode {}")
        val alias = Files.createSymbolicLink(root.resolve("dependency"), ordinary)
        val primary = Files.writeString(sources.resolve("Money.groovy"), "class Money {}")
        val uri = primary.toUri()
        val secondary = alias.resolve("Mode.groovy").toUri()
        val documents = DocumentProvider().apply { put(secondary, "package p\nclass Mode {}", 1) }
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(alias, sources))
        val requests = mutableListOf<ParseRequest>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        requests.add(request)
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(uri, "class Money {}").isSuccess)
            val original = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            val before = requests.size
            assertNotNull(compilation.ensureCompiled(uri))
            assertEquals(before, requests.size, "An unchanged ordinary closed document still uses its cached unit")
            Files.delete(alias)
            Files.createSymbolicLink(alias, managed)

            assertNotNull(compilation.ensureCompiled(uri))

            assertTrue(requests.size > before, "Closed-file cache replay must not bypass secondary input admission")
            assertNotSame(original, assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult)
            assertTrue(requests.drop(before).all { alias !in it.sourceRoots && secondary !in it.openSources })
            assertTrue(requests.drop(before).all { request -> request.workspaceSources.none { it.startsWith(alias) } })
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `restoring an ordinary secondary alias refreshes a closed filtered compilation`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        val sources = Files.createDirectory(root.resolve("src"))
        val declaration = "package p\n@interface Mode {}"
        Files.writeString(Files.createDirectories(ordinary.resolve("p")).resolve("Mode.groovy"), declaration)
        Files.writeString(Files.createDirectories(managed.resolve("p")).resolve("Mode.groovy"), declaration)
        val alias = Files.createSymbolicLink(root.resolve("dependency"), ordinary)
        val content = "import p.Mode\n@Mode class Money {}"
        val uri = Files.writeString(sources.resolve("Money.groovy"), content).toUri()
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(alias, sources))
        val model = manager.current()
        val loaders = linkedSetOf<GroovyClassLoader>()
        val compilation = GroovyCompilationService(
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
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val original = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            val originalBinding = assertNotNull(original.sourceAnnotations.headAt(1, 1))
            val selectedSource = assertNotNull(originalBinding.sourceUnitName)
            assertEquals("p.Mode", originalBinding.binaryName)
            assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))

            Files.delete(alias)
            Files.createSymbolicLink(alias, managed)
            compilation.compile(uri, content)
            val filtered = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertNotSame(original, filtered, "The forward transition must first produce a filtered compilation")
            assertFalse(alias in assertNotNull(filtered.classpathInputs).sourceRoots)
            assertNull(filtered.sourceAnnotations.headAt(1, 1)?.sourceUnitName)

            Files.delete(alias)
            Files.createSymbolicLink(alias, ordinary)
            assertNotNull(compilation.ensureCompiled(uri))
            val restored = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult

            assertTrue(model === manager.current(), "No workspace model update may mask the alias transition")
            assertEquals(
                sourceUnitPath(selectedSource).toRealPath(),
                sourceUnitPath(assertNotNull(restored.sourceAnnotations.headAt(1, 1)?.sourceUnitName)).toRealPath(),
                "Restored ordinary inputs must recover their compiler-selected annotation declaration",
            )
            assertNotNull(
                compilation.captureSourceRequest(uri, "java.lang.String"),
                "The restored compilation must certify the currently admitted source roots",
            )
        } finally {
            compilation.shutdown()
            loaders.forEach(GroovyClassLoader::close)
        }
    }

    @Test
    fun `open aliases retargeted into managed sources are neither parsed nor replayed`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        Files.writeString(ordinary.resolve("Money.groovy"), "class Money {}")
        Files.writeString(managed.resolve("Money.groovy"), "class Money {}")
        val alias = Files.createSymbolicLink(root.resolve("document"), ordinary)
        val uri = alias.resolve("Money.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, "class Money {}", 1) }
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        var parses = 0
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) }),
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        parses++
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(uri, "class Money {}").isSuccess)
            val before = parses
            Files.delete(alias)
            Files.createSymbolicLink(alias, managed)

            assertFalse(
                compilation.compile(uri, "class Money {}").isSuccess,
                "A cached result cannot admit a now-managed document",
            )
            assertNull(compilation.ensureCompiled(uri))
            assertNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            assertEquals(before, parses, "Admission must happen before compiler transforms or secondary source loading")
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `ordinary and stable filtered inputs reuse their warm compilations`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        val sources = Files.createDirectory(root.resolve("src"))
        Files.writeString(ordinary.resolve("Other.groovy"), "class Other {}")
        Files.writeString(managed.resolve("Other.groovy"), "class Other {}")
        val alias = Files.createSymbolicLink(root.resolve("dependency"), ordinary)
        val content = "class Money {}"
        val uri = Files.writeString(sources.resolve("Money.groovy"), content).toUri()
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(alias, sources))
        val parses = AtomicInteger()
        val loaders = PublishedParserLoaders()
        val compilation = GroovyCompilationService(
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        parses.incrementAndGet()
                        val parsed = delegate.parse(request)
                        loaders.record(parsed.compilationUnit.classLoader)
                        return parsed
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val ordinaryResult = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertEquals(1, parses.get(), "The ordinary cold request must actually parse")
            assertTrue(compilation.compile(uri, content).isSuccess)
            assertNotNull(compilation.ensureCompiled(uri))
            assertEquals(1, parses.get(), "Both ordinary warm replay routes must avoid another parser invocation")

            Files.delete(alias)
            Files.createSymbolicLink(alias, managed)
            assertTrue(compilation.compile(uri, content).isSuccess)
            val filtered = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertNotSame(ordinaryResult, filtered)
            assertFalse(alias in assertNotNull(filtered.classpathInputs).sourceRoots)
            assertEquals(2, parses.get(), "The first managed transition must actually produce a filtered parse")

            assertTrue(compilation.compile(uri, content).isSuccess)
            assertNotNull(compilation.ensureCompiled(uri))

            assertEquals(2, parses.get(), "An unchanged filtered projection must reuse both warm replay routes")
            assertTrue(filtered === assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult)
        } finally {
            compilation.shutdown()
            loaders.close()
        }
    }

    @Test
    fun `restoring a secondary file alias refreshes bindings without changing source roots`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        val sources = Files.createDirectory(root.resolve("src"))
        val declaration = "package p\n@interface Mode {}"
        val originalFile = Files.writeString(ordinary.resolve("Mode.groovy"), declaration)
        val managedFile = Files.writeString(managed.resolve("Mode.groovy"), declaration)
        // A nonstandard filename prevents the source-root loader from bypassing selected-file admission.
        val alias = Files.createSymbolicLink(
            Files.createDirectories(sources.resolve("p")).resolve("ImportedAnnotation.groovy"),
            originalFile,
        )
        val content = "import p.Mode\n@Mode class Money {}"
        val uri = Files.writeString(sources.resolve("Money.groovy"), content).toUri()
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(sources))
        val model = manager.current()
        val loaders = PublishedParserLoaders()
        val compilation = GroovyCompilationService(
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        loaders.record(parsed.compilationUnit.classLoader)
                        return parsed
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(alias.toUri(), declaration).isSuccess)
            assertTrue(compilation.compile(uri, content).isSuccess)
            val original = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            val selected = assertNotNull(original.sourceAnnotations.headAt(1, 1))
            assertEquals("p.Mode", selected.binaryName)
            assertEquals(originalFile.toRealPath(), Path.of(assertNotNull(selected.sourceUri)).toRealPath())
            assertTrue(compilation.symbolIndexingService.findClasses(ClassNameQuery.Exact("Mode")).isNotEmpty())

            Files.delete(alias)
            Files.createSymbolicLink(alias, managedFile)
            compilation.compile(uri, content)
            val filtered = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertNotSame(original, filtered)
            assertEquals(listOf(sources), assertNotNull(filtered.classpathInputs).sourceRoots)
            assertNull(filtered.sourceAnnotations.headAt(1, 1)?.sourceUnitName)

            Files.delete(alias)
            Files.createSymbolicLink(alias, originalFile)
            assertNotNull(compilation.ensureCompiled(uri))
            val restored = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult

            assertTrue(model === manager.current())
            val restoredSource = assertNotNull(restored.sourceAnnotations.headAt(1, 1)?.sourceUnitName)
            assertEquals(originalFile.toRealPath(), sourceUnitPath(restoredSource).toRealPath())
            assertEquals("p.Mode", restored.sourceAnnotations.headAt(1, 1)?.binaryName)
        } finally {
            compilation.shutdown()
            loaders.close()
        }
    }

    @Test
    fun `restoring an open secondary URI alias refreshes bindings without changing source roots`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val ordinary = Files.createDirectory(root.resolve("ordinary"))
        val managed = Files.createDirectory(root.resolve("managed"))
        val sources = Files.createDirectory(root.resolve("src"))
        val declaration = "package p\n@interface Mode {}"
        val originalFile = Files.writeString(ordinary.resolve("Mode.groovy"), declaration)
        Files.writeString(managed.resolve("Mode.groovy"), declaration)
        val alias = Files.createSymbolicLink(root.resolve("open-dependency"), ordinary)
        val secondary = alias.resolve("Mode.groovy").toUri()
        val content = "import p.Mode\n@Mode class Money {}"
        val uri = Files.writeString(sources.resolve("Money.groovy"), content).toUri()
        val documents = DocumentProvider().apply { put(secondary, declaration, 1) }
        val registry = ExternalSourceRegistry(root.resolve("views"), managedRoots = listOf(managed))
        val manager = WorkspaceManager(sourceExclusion = { registry.isManaged(it.toUri()) })
        manager.updateWorkspaceModel(root, emptyList(), listOf(sources))
        val model = manager.current()
        val loaders = PublishedParserLoaders()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        loaders.record(parsed.compilationUnit.classLoader)
                        return parsed
                    }
                }
            },
        )
        try {
            assertTrue(compilation.compile(secondary, declaration).isSuccess)
            assertTrue(compilation.compile(uri, content).isSuccess)
            val original = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            val selected = assertNotNull(original.sourceAnnotations.headAt(1, 1))
            assertEquals("p.Mode", selected.binaryName)
            assertEquals(secondary, selected.sourceUri)

            Files.delete(alias)
            Files.createSymbolicLink(alias, managed)
            compilation.compile(uri, content)
            val filtered = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertNotSame(original, filtered)
            assertEquals(listOf(sources), assertNotNull(filtered.classpathInputs).sourceRoots)
            assertNull(filtered.sourceAnnotations.headAt(1, 1)?.sourceUnitName)

            Files.delete(alias)
            Files.createSymbolicLink(alias, ordinary)
            assertNotNull(compilation.ensureCompiled(uri))
            val restored = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult

            assertTrue(model === manager.current())
            assertEquals(declaration, documents.getSnapshot(secondary)?.content)
            val restoredSource = assertNotNull(restored.sourceAnnotations.headAt(1, 1)?.sourceUri)
            assertEquals(originalFile.toRealPath(), Path.of(restoredSource).toRealPath())
            assertEquals("p.Mode", restored.sourceAnnotations.headAt(1, 1)?.binaryName)
        } finally {
            compilation.shutdown()
            loaders.close()
        }
    }

    @Test
    fun `an awaited producer cannot return a source binding retired by secondary admission`(): Unit = runBlocking {
        assertAwaitedAdmissionRefresh(returnUncachedFailure = false)
    }

    @Test
    fun `an uncached awaited failure retains its producer admission instead of borrowing cache authority`(): Unit =
        runBlocking {
            assertAwaitedAdmissionRefresh(returnUncachedFailure = true)
        }

    @Test
    fun `an unchanged uncached failure returns once without inventing cache authority`(): Unit = runBlocking {
        assertAwaitedAdmissionRefresh(returnUncachedFailure = true, retarget = false)
    }

    @Test
    fun `a newer same URI cache entry cannot certify an older uncached completion`(): Unit = runBlocking {
        assertAwaitedAdmissionRefresh(returnUncachedFailure = true, publishNewer = true)
    }

    @Test
    fun `a closed disk producer cannot return a source binding retired by secondary admission`(): Unit = runBlocking {
        assertAwaitedAdmissionRefresh(returnUncachedFailure = false, openPrimary = false)
    }

    @Test
    fun `an uncached closed disk failure cannot return bindings retired by secondary admission`(): Unit = runBlocking {
        assertAwaitedAdmissionRefresh(returnUncachedFailure = true, openPrimary = false)
    }

    private fun sourceUnitPath(sourceUnitName: String): Path {
        val uri = runCatching { URI.create(sourceUnitName) }.getOrNull()
        return if (uri?.scheme == "file") Path.of(uri) else Path.of(sourceUnitName)
    }

    private suspend fun assertAwaitedAdmissionRefresh(
        returnUncachedFailure: Boolean,
        retarget: Boolean = true,
        publishNewer: Boolean = false,
        openPrimary: Boolean = true,
    ) {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        HeldAdmissionFixture(root, returnUncachedFailure, openPrimary = openPrimary).use { fixture ->
            val compilation = fixture.compilation
            val producer = fixture.producer
            val uri = fixture.uri
            if (!openPrimary) assertNull(fixture.documents.getSnapshot(uri), "The disk producer must have no buffer")
            withTimeout(20_000) {
                supervisorScope {
                    val pending = async(Dispatchers.Default) { compilation.ensureCompiled(uri) }
                    pending.invokeOnCompletion { failure ->
                        if (failure != null) producer.entered.completeExceptionally(failure)
                    }
                    try {
                        producer.entered.await()
                        assertNotNull(
                            producer.selectedSource.get(),
                            "The held producer must have selected the ordinary source",
                        )
                        assertEquals(1, producer.parses.get())
                        if (retarget) {
                            Files.delete(fixture.alias)
                            Files.createSymbolicLink(fixture.alias, fixture.managed)
                        }
                        if (publishNewer) {
                            compilation.compile(uri, fixture.content)
                            val newer = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
                            assertNull(newer.sourceAnnotations.headAt(1, 1)?.sourceUnitName)
                        }
                        producer.release.countDown()

                        val result = assertNotNull(pending.await())

                        if (!openPrimary) assertNull(fixture.documents.getSnapshot(uri))
                        producer.assertOutcome(result, retarget)
                        if (!retarget) {
                            assertNull(
                                compilation.getCompiledSnapshot(uri),
                                "No parse was cached for this valid failure",
                            )
                        }
                    } finally {
                        producer.release.countDown()
                        pending.cancelAndJoin()
                    }
                }
            }
        }
    }

    private fun assertRetargetedInputsExcluded(
        requests: List<ParseRequest>,
        alias: Path,
        selected: URI,
        uri: URI,
        unsaved: URI,
    ) {
        assertTrue(requests.all { alias !in it.sourceRoots && selected !in it.openSources })
        assertTrue(requests.all { request -> request.workspaceSources.none { it.startsWith(alias) } })
        assertTrue(
            requests.filter { it.uri == uri }.all { unsaved in it.openSources },
            "Ordinary unsaved files remain inputs",
        )
    }
}
