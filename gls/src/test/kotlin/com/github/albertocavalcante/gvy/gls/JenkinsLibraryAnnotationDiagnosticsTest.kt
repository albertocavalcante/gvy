// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.stream.Stream
import kotlin.test.assertTrue

/**
 * Real-server-path tests for loading Jenkins shared libraries from a Jenkinsfile.
 *
 * Jenkins makes `@Library` available to every Pipeline script without an import. A Jenkinsfile that
 * loads a shared library must therefore compile without errors in Jenkins mode, and the Jenkins
 * diagnostic rules (which skip files with Error diagnostics) must still analyze it.
 */
class JenkinsLibraryAnnotationDiagnosticsTest {

    private var serverHandle: TestLanguageServerHandle? = null
    private lateinit var workspaceRoot: Path

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
        if (::workspaceRoot.isInitialized) {
            workspaceRoot.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest(name = "{0} (configured library: {1})")
    @MethodSource("libraryForms")
    fun `library loading forms compile without errors and keep Jenkins rules running`(
        header: String,
        configureLibrary: Boolean,
    ) {
        val uri = startServerAndOpen(header + "\n" + INCOMPLETE_STAGE_PIPELINE, configureLibrary)

        // Compile errors and rule results arrive in one publish; the stage rule only runs on files
        // without Error diagnostics, so waiting for its warning also waits for the compile result.
        val diagnostics = awaitDiagnosticsMatching(uri) { published ->
            published.any { it.message.contains("Build") && it.message.contains("steps") }
        }

        assertTrue(
            diagnostics.none { it.severity == DiagnosticSeverity.Error },
            "Expected no Error diagnostics for '$header' but got:\n${describe(diagnostics)}",
        )
        assertTrue(
            diagnostics.any { it.message.contains("Build") && it.message.contains("steps") },
            "Expected the Jenkins stage rule to report the incomplete stage for '$header' but got:\n" +
                describe(diagnostics),
        )
    }

    @Test
    fun `misspelled library annotation is still reported as an error`() {
        val uri = startServerAndOpen("@Libary('lib') _\n" + INCOMPLETE_STAGE_PIPELINE, configureLibrary = false)

        val diagnostics = awaitDiagnosticsMatching(uri) { published ->
            published.any { it.severity == DiagnosticSeverity.Error }
        }

        assertTrue(
            diagnostics.any { it.severity == DiagnosticSeverity.Error && it.message.contains("Libary") },
            "Expected an unresolved-class error for the misspelled annotation but got:\n${describe(diagnostics)}",
        )
    }

    private fun startServerAndOpen(content: String, configureLibrary: Boolean): String {
        workspaceRoot = Files.createTempDirectory("jenkins-library-annotation-test")
        val jenkinsfile = workspaceRoot.resolve("Jenkinsfile")
        Files.writeString(jenkinsfile, content)
        // A shared library class in the workspace src/ root, so the imported class resolves.
        val libraryClass = workspaceRoot.resolve("src/org/foo/Bar.groovy")
        Files.createDirectories(libraryClass.parent)
        Files.writeString(libraryClass, "package org.foo\n\nclass Bar {}\n")

        val handle = TestLanguageServerRunner().startInMemoryServer()
        serverHandle = handle

        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "test"))
            if (configureLibrary) {
                initializationOptions = mapOf(
                    "jenkins.sharedLibraries" to listOf(
                        mapOf("name" to "lib", "jar" to workspaceRoot.resolve("missing-lib.jar").toString()),
                    ),
                )
            }
        }
        handle.server.initialize(initParams).get()
        handle.server.initialized(InitializedParams())
        handle.client.awaitQuiescentStatus()

        val uri = jenkinsfile.toUri().toString()
        handle.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem(uri, "groovy", 1, content)
            },
        )
        return uri
    }

    /**
     * Returns the first published diagnostics for [uri] that satisfy [predicate], or the last
     * published diagnostics when none do before the timeout.
     */
    private fun awaitDiagnosticsMatching(uri: String, predicate: (List<Diagnostic>) -> Boolean): List<Diagnostic> {
        val client = requireNotNull(serverHandle).client
        val deadline = System.nanoTime() + DIAGNOSTICS_TIMEOUT_MS * NANOS_PER_MILLI
        var last: PublishDiagnosticsParams? = null
        while (System.nanoTime() < deadline) {
            val remainingMs = ((deadline - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(1)
            val published = try {
                client.awaitDiagnosticsForUri(uri, remainingMs)
            } catch (_: TimeoutException) {
                break
            }
            last = published
            if (predicate(published.diagnostics)) return published.diagnostics
        }
        return last?.diagnostics ?: throw AssertionError("No diagnostics published for $uri")
    }

    private fun describe(diagnostics: List<Diagnostic>): String = diagnostics.joinToString("\n") {
        "  ${it.severity} line ${it.range.start.line}: ${it.message}"
    }

    companion object {
        private const val DIAGNOSTICS_TIMEOUT_MS = 30_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        private val INCOMPLETE_STAGE_PIPELINE = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        echo 'Building...'
                    }
                }
            }
        """.trimIndent()

        @JvmStatic
        fun libraryForms(): Stream<Arguments> = listOf(
            "@Library('lib') _",
            "@Library(['lib', 'other']) _",
            "@Library('lib@1.0') import org.foo.Bar",
            "library 'lib'",
        ).flatMap { header -> listOf(false, true).map { Arguments.of(header, it) } }.stream()
    }
}
