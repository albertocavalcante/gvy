// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceLookupContextTest {
    @Test
    fun `ambiguous owner still hides globally proven exclusions`(@TempDir root: Path) {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val request = sourceRoot.resolve("Shared.groovy")
        val excluded = sourceRoot.resolve("Excluded.groovy")
        val dependency = sourceRoot.resolve("Dependency.groovy")
        fun target(id: String, files: List<Path>) = TargetDependencySnapshot(
            target = DependencyTarget(URI.create("test:$id"), root, id),
            role = DependencyRole.COMPILE,
            provenance = DependencyProvenance("test", 1),
            state = TargetDependencyState.COMPLETE,
            artifacts = emptyList(),
            sourceRoots = listOf(sourceRoot),
            sourceFiles = files,
        )
        val evidence = TargetDependencyEvidence.Scoped(
            listOf(target("a", listOf(request)), target("b", listOf(request, dependency))),
            enumerationComplete = true,
        )
        val service = GroovyCompilationService()
        service.updateWorkspaceModel(root, emptyList(), listOf(sourceRoot), evidence)
        val index = service.getWorkspaceSymbolIndex()!!
        fun add(path: Path, name: String) {
            val id = "example/$name#"
            val symbol = SymbolInfo(id, SymbolKind.CLASS, Range(0, 0, 0, 1), name, null)
            index.updateDocument(path.toUri(), SemanticDocument(path.toUri(), listOf(symbol), emptyList()))
        }
        add(request, "Self")
        add(excluded, "Excluded")
        add(dependency, "Dependency")
        val lookup = service.captureWorkspaceLookup(request.toUri())
        assertEquals(request.toUri().toString(), lookup.index.findDefinition("example/Self#")?.uri)
        assertEquals(dependency.toUri().toString(), lookup.index.findDefinition("example/Dependency#")?.uri)
        assertNull(lookup.index.findDefinition("example/Excluded#"))
        service.clearCaches()
    }

    @Test
    fun `strategy override keeps unmodeled source visible to indexed lookup`(@TempDir root: Path) {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val ordinary = sourceRoot.resolve("Main.groovy").toUri()
        val jenkins = sourceRoot.resolve("Jenkinsfile.groovy").toUri()
        val outsideGradle = sourceRoot.resolve("Shared.groovy").toUri()
        val target = TargetDependencySnapshot(
            target = DependencyTarget(URI.create("test:main"), root, "main"),
            role = DependencyRole.COMPILE,
            provenance = DependencyProvenance("test", 1),
            state = TargetDependencyState.COMPLETE,
            artifacts = emptyList(),
            sourceRoots = listOf(sourceRoot),
            sourceFiles = listOf(Path.of(ordinary)),
        )
        val service = GroovyCompilationService()
        service.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(sourceRoot),
            TargetDependencyEvidence.Scoped(listOf(target), enumerationComplete = true),
        )
        val strategy = object : ProjectStrategy {
            override val id = "test-strategy"
            override val displayName = "Test strategy"
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun overridesClasspath(uri: URI) = uri == jenkins
        }
        val registry = ProjectStrategyRegistry(listOf(strategy))
        registry.selectStrategies(root, ServerConfiguration())
        service.workspaceManager.setStrategyRegistry(registry)
        val symbol = SymbolInfo("example/Shared#", SymbolKind.CLASS, Range(0, 0, 0, 1), "Shared", null)
        service.getWorkspaceSymbolIndex()!!.updateDocument(
            outsideGradle,
            SemanticDocument(outsideGradle, listOf(symbol), emptyList()),
        )

        assertNull(service.captureWorkspaceLookup(ordinary).index.findDefinition("example/Shared#"))
        assertEquals(
            outsideGradle.toString(),
            service.captureWorkspaceLookup(jenkins).index.findDefinition("example/Shared#")?.uri,
        )
        service.clearCaches()
    }

    @Test
    fun `lookup retires when strategy applicability changes without workspace replacement`(@TempDir root: Path) {
        val service = GroovyCompilationService()
        service.workspaceManager.initializeWorkspace(root)
        val uri = root.resolve("Jenkinsfile.groovy").toUri()
        val active = AtomicBoolean(true)
        val strategy = object : ProjectStrategy {
            override val id = "changing-strategy"
            override val displayName = "Changing strategy"
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun overridesClasspath(uri: URI) = active.get()
        }
        val registry = ProjectStrategyRegistry(listOf(strategy))
        registry.selectStrategies(root, ServerConfiguration())
        service.workspaceManager.setStrategyRegistry(registry)
        val symbol = SymbolInfo("example/Example#", SymbolKind.CLASS, Range(0, 0, 0, 1), "Example", null)
        service.getWorkspaceSymbolIndex()!!.updateDocument(uri, SemanticDocument(uri, listOf(symbol), emptyList()))
        val workspace = service.workspaceManager.current()
        val lookup = service.captureWorkspaceLookup(uri)
        assertTrue(lookup.isCurrent())
        assertEquals(uri.toString(), lookup.index.findDefinition("example/Example#")?.uri)

        active.set(false)
        assertTrue(service.workspaceManager.current() === workspace)
        assertFalse(lookup.isCurrent())
        assertNull(lookup.index.findDefinition("example/Example#"))
        service.clearCaches()
    }

    @Test
    fun `lookup paused across workspace replacement declines old visibility`(@TempDir root: Path) = runTest {
        val service = GroovyCompilationService()
        service.workspaceManager.initializeWorkspace(root)
        val uri = root.resolve("Example.groovy").toUri()
        val symbol = SymbolInfo("example/Example#", SymbolKind.CLASS, Range(0, 0, 0, 1), "Example", null)
        service.getWorkspaceSymbolIndex()!!.updateDocument(uri, SemanticDocument(uri, listOf(symbol), emptyList()))
        val lookup = service.captureWorkspaceLookup(uri)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pending = async(Dispatchers.Default) {
            entered.countDown()
            check(resume.await(10, TimeUnit.SECONDS))
            lookup.index.findDefinition("example/Example#")
        }
        assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
        service.workspaceManager.initializeWorkspace(java.nio.file.Files.createDirectory(root.resolve("new-workspace")))
        assertFalse(lookup.isCurrent())
        resume.countDown()
        assertNull(pending.await())
        service.clearCaches()
    }

    @Test
    fun `independent sources cannot resolve unrelated untitled documents`(@TempDir root: Path) {
        val included = root.resolve("Included.groovy")
        val target = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:main"), root, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(root),
            listOf(included),
        )
        val service = GroovyCompilationService()
        try {
            service.updateWorkspaceModel(
                root,
                emptyList(),
                listOf(root),
                TargetDependencyEvidence.Scoped(listOf(target), enumerationComplete = true),
            )
            val other = URI.create("untitled:Other")
            val self = URI.create("untitled:Self")
            val index = checkNotNull(service.getWorkspaceSymbolIndex())
            listOf(other to "Other", self to "Self").forEach { (uri, name) ->
                val symbol = SymbolInfo("example/$name#", SymbolKind.CLASS, Range(0, 0, 0, 1), name, null)
                index.updateDocument(uri, SemanticDocument(uri, listOf(symbol), emptyList()))
            }
            val independent = service.captureWorkspaceLookup(self)
            assertEquals(self.toString(), independent.index.findDefinition("example/Self#")?.uri)
            assertNull(independent.index.findDefinition("example/Other#"))
            assertNull(
                service.captureWorkspaceLookup(root.resolve("Excluded.groovy").toUri())
                    .index.findDefinition("example/Other#"),
            )
            assertEquals(
                other.toString(),
                service.captureWorkspaceLookup(included.toUri())
                    .index.findDefinition("example/Other#")?.uri,
                "selected sources retain unknown recovery",
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `model replacement retires facts before readers can capture its policy`(@TempDir root: Path) = runTest {
        val service = GroovyCompilationService()
        service.initializeWorkspace(root)
        val uri = root.resolve("A.groovy").toUri()
        val index = checkNotNull(service.getWorkspaceSymbolIndex())
        val symbol = SymbolInfo("example/A#", SymbolKind.CLASS, Range(0, 0, 0, 1), "A", null)
        index.updateDocument(uri, SemanticDocument(uri, listOf(symbol), emptyList()))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        var cleanup: () -> Unit = {}
        val updating = async(Dispatchers.IO) {
            service.workspaceManager.updateWorkspaceModel(
                root,
                emptyList(),
                listOf(root),
                TargetDependencyEvidence.Scoped(emptyList()),
            ) {
                entered.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                cleanup = service.prepareCacheRetirement()
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            val reading = async(Dispatchers.Default) {
                readerStarted.countDown()
                service.captureWorkspaceLookup(uri).takeIf { it.isCurrent() }.also { readerFinished.countDown() }
            }
            assertTrue(withContext(Dispatchers.IO) { readerStarted.await(10, TimeUnit.SECONDS) })
            assertFalse(withContext(Dispatchers.IO) { readerFinished.await(100, TimeUnit.MILLISECONDS) })
            resume.countDown()
            updating.await()
            val lookup = reading.await()
            assertNull(lookup?.index?.findDefinition("example/A#"))
        } finally {
            resume.countDown()
            updating.join()
            cleanup()
            service.shutdown()
        }
    }
}
