// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceModeQualityTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `closed navigation preserves stronger references only in their original window`(
        retired: Boolean,
        @TempDir root: Path,
    ): Unit = runBlocking {
        // WORKSPACE sees this class by membership. MINIMAL has neither a matching filename nor a symbol index entry.
        val support = Files.writeString(root.resolve("Declarations.groovy"), "class AuxiliaryBase {}")
        val text = "class Subject extends AuxiliaryBase { void local() {} }"
        val path = Files.writeString(root.resolve("Subject.groovy"), text)
        val uri = path.toUri()
        val requests = mutableListOf<ParseRequest>()
        val compilation = GroovyCompilationService(workerSessionDecorator = { worker ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri == uri) requests.add(request)
                    return worker.parse(request)
                }
            }
        })
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            assertTrue(compilation.getWorkspaceCompiler().compileWorkspace().success)
            compilation.refreshClosedReferences(path, ParseMode.WORKSPACE)
            val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
            val healthy = assertNotNull(index.getDocument(uri))
            assertEquals(Phases.CANONICALIZATION, compilation.semanticPhaseOf(uri))
            val ready = compilation.referenceReadiness(uri).query { it.modeFor(uri) }
            assertEquals(ParseMode.WORKSPACE, assertIs<SnapshotQuery.Answer<*>>(ready).value)
            assertNull(compilation.getCompiledContent(uri), "Closed navigation must take the disk compilation route")

            val original = compilation.captureSnapshotSource(uri)
            if (retired) assertNotNull(compilation.onClosedSourceChanged(uri, keepIfUnchanged = false))
            assertEquals(!retired, original.isCurrent())
            assertSame(healthy, index.getDocument(uri), "Retirement keeps the old document until refresh")

            compilation.ensureCompiled(uri)
            val weaker = assertNotNull(compilation.getParseResult(uri))
            assertNotNull(weaker.ast)
            assertTrue(weaker.outcome.isDegraded, "The actual MINIMAL worker must lose type resolution")
            assertTrue(weaker.outcome.achievedPhase <= Phases.CANONICALIZATION)
            assertSameInputsAcrossModes(requests, text, support)
            val current = assertNotNull(index.getDocument(uri))
            val coverage = compilation.referenceReadiness(uri).query { it.modeFor(uri) }
            if (retired) {
                assertNotSame(healthy, current, "The earlier window cannot retain authority after retirement")
                assertEquals(weaker.outcome.achievedPhase, compilation.semanticPhaseOf(uri))
                assertIs<SnapshotQuery.Failure>(coverage)
            } else {
                assertSame(healthy, current, "A later degraded MINIMAL parse displaced the healthy WORKSPACE document")
                assertEquals(Phases.CANONICALIZATION, compilation.semanticPhaseOf(uri))
                assertEquals(ParseMode.WORKSPACE, assertIs<SnapshotQuery.Answer<*>>(coverage).value)
            }
        } finally {
            compilation.shutdown()
        }
    }

    private fun assertSameInputsAcrossModes(requests: List<ParseRequest>, text: String, support: Path) {
        val minimal = requests.last()
        val workspace = requests.last { it.parseMode == ParseMode.WORKSPACE }
        assertEquals(ParseMode.MINIMAL, minimal.parseMode)
        assertEquals(ParseMode.WORKSPACE, workspace.parseMode)
        assertEquals(text, minimal.content)
        assertEquals(workspace.content, minimal.content)
        assertEquals(workspace.classpath, minimal.classpath)
        assertEquals(workspace.openSources, minimal.openSources)
        assertEquals(workspace.workspaceSources, minimal.workspaceSources)
        assertTrue(support in workspace.workspaceSources)
        assertEquals(workspace.sourceRoots, minimal.sourceRoots)
    }
}
