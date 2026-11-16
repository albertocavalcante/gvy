// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SourceMembershipRefreshTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `source events debounce membership refresh`() = runTest {
        val src = Files.createDirectories(root.resolve("src"))
        val compilation = GroovyCompilationService()
        compilation.updateWorkspaceModel(root, emptyList(), listOf(src))
        var refreshes = 0
        val workspace = GroovyWorkspaceService(
            compilation,
            compilation.symbolIndexingService,
            backgroundScope,
            sourceChangeDispatcher = StandardTestDispatcher(testScheduler),
            refreshSourceMembership = { refreshes++ },
        )
        val events = listOf("A.groovy", "B.groovy").map {
            FileEvent(src.resolve(it).toUri().toString(), FileChangeType.Created)
        }
        workspace.didChangeWatchedFiles(DidChangeWatchedFilesParams(events))
        workspace.didChangeWatchedFiles(DidChangeWatchedFilesParams(events))
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(1, refreshes)
    }

    @Test
    fun `refresh recognizes symlinked and not-yet-created configured roots`() = runTest {
        val real = Files.createDirectories(root.resolve("real"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        val missing = root.resolve("custom/missing")
        val compilation = GroovyCompilationService()
        val target = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:main"), root, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(link, missing),
            emptyList(),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(link),
            TargetDependencyEvidence.Scoped(listOf(target)),
        )
        var refreshes = 0
        val workspace = GroovyWorkspaceService(
            compilation,
            compilation.symbolIndexingService,
            backgroundScope,
            sourceChangeDispatcher = StandardTestDispatcher(testScheduler),
            refreshSourceMembership = { refreshes++ },
        )
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(
                    FileEvent(real.resolve("New.groovy").toUri().toString(), FileChangeType.Created),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(1, refreshes)
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(
                    FileEvent(missing.resolve("First.groovy").toUri().toString(), FileChangeType.Created),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, refreshes)
    }

    @Test
    fun `membership reassignment rebuilds both indices for unchanged closed sources`(): Unit = runBlocking {
        val src = Files.createDirectories(root.resolve("src"))
        val files = listOf("A", "B").map { name ->
            Files.writeString(src.resolve("$name.groovy"), "class $name {}")
        }
        val compilation = GroovyCompilationService()
        fun evidence(targetId: String) = TargetDependencyEvidence.Scoped(
            listOf(
                TargetDependencySnapshot(
                    DependencyTarget(URI.create("test:$targetId"), root, targetId),
                    DependencyRole.COMPILE,
                    DependencyProvenance("test", 1),
                    TargetDependencyState.COMPLETE,
                    emptyList(),
                    listOf(src),
                    files,
                ),
            ),
            enumerationComplete = true,
        )
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(src), evidence("before"))
            compilation.indexAllWorkspaceSources(files.map { it.toUri() })
            compilation.getWorkspaceCompiler().compileWorkspace()
            val semantic = checkNotNull(compilation.getWorkspaceSymbolIndex())
            files.forEach { file ->
                assertNotNull(compilation.symbolIndexingService.getSymbolIndex(file.toUri()))
                assertNotNull(semantic.getDocument(file.toUri()))
            }
            val retired = compilation.workspaceManager.current()
            val oldPolicy = compilation.workspaceManager.captureSourcePolicy(files.first().toUri(), retired)
            coroutineScope {
                val startup = ProjectStartupManager(compilation, emptyList(), this, ProjectStrategyRegistry())
                val refresh = startup.createMembershipCompletionCallback(root, ServerConfiguration()) {}
                refresh(WorkspaceResolution(emptyList(), listOf(src), targetDependencies = evidence("after")))
            }
            files.forEach { file ->
                assertNotNull(compilation.symbolIndexingService.getSymbolIndex(file.toUri()))
                assertNotNull(semantic.getDocument(file.toUri()))
            }
            val current = compilation.workspaceManager.current()
            assertTrue(current !== retired)
            assertTrue(!oldPolicy.isCurrent(), "the old target policy cannot publish into the rebuilt indices")
            assertEquals(files.toSet(), current.sources.toSet(), "no added path was needed to restore either index")
        } finally {
            compilation.shutdown()
        }
    }
}
