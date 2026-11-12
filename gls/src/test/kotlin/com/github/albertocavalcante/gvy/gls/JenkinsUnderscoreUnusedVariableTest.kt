// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `@Library('lib') _` is the idiomatic Jenkins shared-library header: `_` only carries the annotation.
 * CodeNarc's UnusedVariable must not report it, while real unused variables must still be reported.
 *
 * Most cases use `@SuppressWarnings` as the carrier annotation: it has the same AST shape (an annotated
 * declaration of `_`) and resolves without any Jenkins stubs. The `@Library` case covers the real header,
 * which compiles now that Jenkins mode provides a `Library` annotation stub.
 */
class JenkinsUnderscoreUnusedVariableTest {

    private var serverHandle: TestLanguageServerHandle? = null
    private lateinit var workspaceRoot: Path

    @BeforeEach
    fun setup() {
        workspaceRoot = Files.createTempDirectory("jenkins-underscore-test")
        // A Jenkinsfile on disk makes project detection pick the Jenkins CodeNarc ruleset.
        Files.writeString(workspaceRoot.resolve("Jenkinsfile"), "")
        val runner = TestLanguageServerRunner()
        serverHandle = runner.startInMemoryServer()
        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "test"))
        }
        serverHandle!!.server.initialize(initParams).get()
        serverHandle!!.server.initialized(InitializedParams())
        serverHandle!!.client.awaitQuiescentStatus()
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
        workspaceRoot.toFile().deleteRecursively()
    }

    @Test
    fun `underscore carrying an annotation is not reported but a real unused variable is`(): Unit = runBlocking {
        val diagnostics = diagnosticsAfterCodeNarcRuns(
            "@SuppressWarnings('lib') _\n\ndef unusedThing = 1\n\npipeline {\n    agent any\n}\n",
        )

        val messages = diagnostics.map { it.message }
        assertTrue(
            messages.any { it.contains("[unusedThing]") },
            "A real unused variable must still be reported, but was: $messages",
        )
        assertFalse(
            messages.any { it.contains("[_]") },
            "The annotated underscore must not be reported as unused, but was: $messages",
        )
    }

    @Test
    fun `underscore carrying an annotation with a list argument is not reported`(): Unit = runBlocking {
        val diagnostics = diagnosticsAfterCodeNarcRuns(
            "@SuppressWarnings(['a', 'b']) _\n\ndef unusedThing = 1\n\npipeline {\n    agent any\n}\n",
        )

        val messages = diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("[unusedThing]") }, "Real unused variable expected, but was: $messages")
        assertFalse(messages.any { it.contains("[_]") }, "Underscore must not be reported, but was: $messages")
    }

    @Test
    fun `the real Library header underscore is not reported`(): Unit = runBlocking {
        val diagnostics = diagnosticsAfterCodeNarcRuns(
            "@Library('lib') _\n\ndef unusedThing = 1\n\npipeline {\n    agent any\n}\n",
        )

        val messages = diagnostics.map { it.message }
        assertFalse(
            diagnostics.any { it.severity == DiagnosticSeverity.Error },
            "The @Library header must compile cleanly, but was: $messages",
        )
        assertTrue(messages.any { it.contains("[unusedThing]") }, "Real unused variable expected, but was: $messages")
        assertFalse(messages.any { it.contains("[_]") }, "Underscore must not be reported, but was: $messages")
    }

    @Test
    fun `an unused variable named like an underscore prefix is still reported`(): Unit = runBlocking {
        val diagnostics = diagnosticsAfterCodeNarcRuns(
            "def _unused = 1\ndef unusedThing = 1\n\npipeline {\n    agent any\n}\n",
        )

        val messages = diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("[unusedThing]") }, "Real unused variable expected, but was: $messages")
        assertTrue(messages.any { it.contains("[_unused]") }, "Only exactly _ is exempt, but was: $messages")
    }

    /** Opens a Jenkinsfile and returns the first published diagnostics that include the unusedThing report. */
    private fun diagnosticsAfterCodeNarcRuns(content: String): List<Diagnostic> {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        serverHandle!!.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem().apply {
                    this.uri = uri
                    languageId = "groovy"
                    version = 1
                    text = content
                }
            },
        )
        val client = serverHandle!!.client
        val deadline = System.nanoTime() + 60_000_000_000L
        var last: List<Diagnostic> = emptyList()
        while (System.nanoTime() < deadline) {
            try {
                last = client.awaitDiagnosticsForUri(uri, 30_000).diagnostics
            } catch (_: TimeoutException) {
                break
            }
            if (last.any { it.message.contains("[unusedThing]") }) return last
        }
        return last
    }
}
