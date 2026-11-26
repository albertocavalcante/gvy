// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompileReuseWindowTest {
    @Test
    fun `unchanged companion inputs reuse the warm public compile`(@TempDir root: Path) {
        verifyReuse(root, editCompanion = false)
    }

    @Test
    fun `a companion edit during producer context validation forces a fresh public compile`(@TempDir root: Path) {
        verifyReuse(root, editCompanion = true)
    }

    private fun verifyReuse(root: Path, editCompanion: Boolean): Unit = runBlocking {
        val uri = root.resolve("Subject.groovy").toUri()
        val supportPath = Files.writeString(root.resolve("Support.groovy"), "class Support { int before }")
        val support = supportPath.toUri()
        val text = "class Subject extends Support { def target() {} }"
        val before = Files.readString(supportPath)
        val after = "class Support { String after }"
        val documents = DocumentProvider().apply {
            put(uri, text)
            put(support, before)
        }
        var armed = false
        var lookups = 0
        var edited = false
        val manager = WorkspaceManager().apply {
            setTestClasspath(root) {
                if (armed) {
                    lookups++
                    // The first lookup selects the requester context. The second validates the cached producer
                    // after its original inventory comparison, immediately before the reuse decision.
                    if (lookups == 2 && editCompanion) {
                        documents.put(support, after)
                        edited = true
                    }
                }
                emptyList()
            }
        }
        val requests = mutableListOf<ParseRequest>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        if (request.uri == uri) requests.add(request)
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.compile(uri, text)
            val original = assertOriginalParse(compilation, uri, requests, supportPath, before)
            val inventory = documents.captureDocuments()

            armed = true
            compilation.compile(uri, text)
            armed = false
            assertTrue(lookups >= 2, "the cached producer context lookup must execute")
            assertEquals(editCompanion, edited)
            if (editCompanion) {
                assertTrue(inventory !== documents.captureDocuments(), "the hook must retire the captured inventory")
                assertEquals(2, requests.size, "a retired producer must not be reused or republished")
                assertEquals(after, requests.last().openSources[support])
                val refreshed = assertNotNull(compilation.getParseResult(uri))
                assertTrue(refreshed.isSuccessful)
                assertFalse(refreshed.outcome.isDegraded)
                assertTrue(refreshed !== original)
            } else {
                assertSame(inventory, documents.captureDocuments())
                assertEquals(1, requests.size)
                assertSame(original, compilation.getParseResult(uri))
            }
        } finally {
            compilation.shutdown()
        }
    }

    private fun assertOriginalParse(
        compilation: GroovyCompilationService,
        uri: URI,
        requests: List<ParseRequest>,
        supportPath: Path,
        content: String,
    ): ParseResult {
        val original = assertNotNull(compilation.getParseResult(uri))
        assertTrue(original.isSuccessful)
        assertFalse(original.outcome.isDegraded)
        assertEquals(Phases.CANONICALIZATION, original.outcome.achievedPhase)
        assertEquals(1, requests.size)
        assertEquals(ParseMode.WORKSPACE, requests.single().parseMode)
        assertEquals(content, requests.single().openSources[supportPath.toUri()])
        assertTrue(supportPath in requests.single().workspaceSources)
        return original
    }
}
