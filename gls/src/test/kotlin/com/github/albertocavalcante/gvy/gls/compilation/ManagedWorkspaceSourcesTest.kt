// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManagedWorkspaceSourcesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `managed source trees are excluded from discovery and watched folders while ordinary copies remain`() {
        val managed = root.resolve(".gls/cache/extracted-sources")
        val cached = source(managed.resolve("legacy/p/Mode.groovy"))
        val copied = source(root.resolve("src/p/Mode.groovy"))
        val unrelatedCache = source(root.resolve(".cache/local/Other.groovy"), "class Other {}")
        val registry = registry(managed)
        val excluded: (Path) -> Boolean = { registry.isManaged(it.toUri()) }
        val scanner = WorkspaceScanner(excluded)

        assertEquals(setOf(copied, unrelatedCache), scanner.findGroovyFiles(listOf(root)).toSet())
        assertTrue(scanner.findGroovyFiles(listOf(managed)).none())
        val manager = WorkspaceManager(sourceExclusion = excluded)
        manager.updateWorkspaceModel(root, emptyList(), listOf(root))
        assertEquals(setOf(copied, unrelatedCache), manager.getWorkspaceSources().toSet())

        val newlyCached = source(managed.resolve("new/p/Second.groovy"), "class Second {}")
        assertTrue(manager.applySourceChanges(listOf(newlyCached, managed), emptyList()).added.isEmpty())
        assertEquals(setOf(copied, unrelatedCache), manager.getWorkspaceSources().toSet())

        manager.updateWorkspaceModel(root, emptyList(), listOf(managed))
        assertTrue(manager.getSourceRoots().isEmpty())
        assertTrue(manager.getWorkspaceSources().isEmpty())
        assertTrue(Files.exists(cached), "Exclusion never removes source files")
    }

    @Test
    fun `managed file links and an explicit root alias are not admitted as workspace sources`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = root.resolve("managed")
        val cached = source(managed.resolve("Mode.groovy"))
        val ordinary = source(root.resolve("src/Mode.groovy"))
        val fileAlias = Files.createSymbolicLink(root.resolve("src/Cached.groovy"), cached)
        val rootAlias = Files.createSymbolicLink(root.resolve("cache-alias"), managed)
        val registry = registry(managed)
        val excluded: (Path) -> Boolean = { registry.isManaged(it.toUri()) }
        val scanner = WorkspaceScanner(excluded)

        assertEquals(listOf(ordinary), scanner.findGroovyFiles(listOf(root.resolve("src"))).toList())
        assertTrue(scanner.findGroovyFiles(listOf(rootAlias)).none())
        val manager = WorkspaceManager(sourceExclusion = excluded)
        manager.updateWorkspaceModel(root, emptyList(), listOf(root.resolve("src"), rootAlias))
        assertEquals(listOf(ordinary), manager.getWorkspaceSources())
        assertTrue(manager.applySourceChanges(listOf(fileAlias), emptyList()).added.isEmpty())
    }

    @Test
    fun `managed same-name declarations never enter workspace symbol or semantic indexes`(): Unit = runBlocking {
        val managed = root.resolve("managed")
        val cached = source(managed.resolve("Mode.groovy"))
        val copied = source(root.resolve("src/Mode.groovy"))
        val registry = registry(managed)
        val compilation = GroovyCompilationService(
            workspaceManager = WorkspaceManager(sourceExclusion = { path -> registry.isManaged(path.toUri()) }),
        )
        try {
            assertFalse(compilation.compile(cached.toUri(), Files.readString(cached)).isSuccess)
            assertTrue(compilation.compile(copied.toUri(), Files.readString(copied)).isSuccess)

            assertEquals(setOf(copied.toUri()), compilation.symbolIndexingService.getAllSymbolIndices().keys)
            val semantic = assertNotNull(compilation.getWorkspaceSymbolIndex())
            assertEquals(setOf(copied.toUri()), semantic.implementationSnapshot.keys)
            assertEquals(1, compilation.symbolIndexingService.findClasses(ClassNameQuery.Exact("Mode")).size)
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `a managed declaration opened through a linked ancestor cannot shadow an ordinary copy`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = root.resolve("managed")
        source(managed.resolve("Mode.groovy"))
        val copied = source(root.resolve("src/Mode.groovy"))
        val alias = Files.createSymbolicLink(root.resolve("src/cache-alias"), managed).resolve("Mode.groovy")
        val registry = registry(managed)
        val compilation = GroovyCompilationService(
            workspaceManager = WorkspaceManager(sourceExclusion = { path -> registry.isManaged(path.toUri()) }),
        )
        try {
            assertFalse(compilation.compile(alias.toUri(), Files.readString(alias)).isSuccess)
            assertTrue(compilation.compile(copied.toUri(), Files.readString(copied)).isSuccess)

            assertEquals(setOf(copied.toUri()), compilation.symbolIndexingService.getAllSymbolIndices().keys)
            assertEquals(
                setOf(copied.toUri()),
                assertNotNull(compilation.getWorkspaceSymbolIndex()).implementationSnapshot.keys,
            )
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `a differently cased workspace root cannot expose managed descendants`() {
        val workspace = Files.createDirectory(root.resolve("CaseWorkspace"))
        val alias = root.resolve("caseworkspace")
        assumeTrue(Files.isDirectory(alias), "A case-insensitive filesystem is required")
        val managed = workspace.resolve("managed")
        source(managed.resolve("Mode.groovy"))
        val ordinary = source(workspace.resolve("src/Mode.groovy"))
        val registry = registry(managed)
        val excluded: (Path) -> Boolean = { registry.isManaged(it.toUri()) }

        val scanned = WorkspaceScanner(excluded).findGroovyFiles(listOf(alias)).toList()
        assertEquals(listOf(ordinary.toRealPath()), scanned.map { it.toRealPath() })
        val manager = WorkspaceManager(sourceExclusion = excluded)
        manager.updateWorkspaceModel(alias, emptyList(), listOf(alias))
        assertEquals(listOf(ordinary.toRealPath()), manager.getWorkspaceSources().map { it.toRealPath() })
        source(managed.resolve("Later.groovy"), "class Later {}")
        assertTrue(
            manager.applySourceChanges(listOf(alias.resolve("managed/Later.groovy")), emptyList()).added.isEmpty(),
        )
    }

    private fun registry(managed: Path) =
        ExternalSourceRegistry(root.resolve("browser"), managedRoots = listOf(managed))

    private fun source(path: Path, content: String = "package p\nclass Mode {}") = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, content)
    }
}
