// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.NativeRetryInputs
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleFreeInputWireTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `CLI retains a final implicit physical syntax failure without a module`(edited: Boolean): Unit = runBlocking {
        val uri = root.resolve("Primary.groovy").toUri()
        val sources = Files.createDirectory(root.resolve("sources"))
        val implicit = sources.resolve("Broken.groovy")
        val source = "class Primary { Outer.Inner primaryError; Broken input }"
        val reached = AtomicBoolean()
        withInvocationProof({ request, parsed ->
            assertEquals(uri, request.uri)
            assertEquals(listOf(sources), request.sourceRoots)
            assertTrue(request.workspaceSources.isEmpty() && request.openSources.isEmpty())
            val history = assertNotNull(NativeRetryInputs.of(parsed))
            assertFalse(history.hasDiscarded, "Probe precondition: this runtime retried the implicit syntax failure")
            assertTrue(parsed.outcome.excludedSources.isEmpty() && parsed.outcome.stubbedClasses.isEmpty())
            val modules = parsed.compilationUnit.ast.modules
            assertTrue(modules.none { it.context?.name?.let(::filePath) == implicit })
            val owner = modules.single { it.context === parsed.sourceUnit }
            val membership = assertNotNull(NativeSourceMembership.of(owner))
            assertEquals(NativeSourceMembership.Status.CURRENT, membership.check {})
            val locators = parsed.compilationUnit.errorCollector.errors.orEmpty()
                .filterIsInstance<SyntaxErrorMessage>().mapNotNull { it.cause.sourceLocator }
            assertTrue(locators.any { filePath(it) == implicit }, "No final syntax error from $implicit in $locators")
            assertTrue(parsed.diagnostics.any { it.severity == ParserSeverity.ERROR && "Outer.Inner" in it.message })
            reached.set(true)
            if (edited) {
                val time = Files.getLastModifiedTime(implicit)
                Files.writeString(implicit, "class Broken { void n( }")
                Files.setLastModifiedTime(implicit, time)
            }
            parsed
        }) {
            compiler.updateWorkspaceModel(root, emptyList(), listOf(sources))
            Files.writeString(implicit, "class Broken { void m( }")
            assertTrue(compiler.workspaceManager.current().sources.isEmpty())
            open(uri, source)
            if (edited) {
                val error = assertFailsWith<ResponseErrorException> { cli.diagnose(uri, source) }
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            } else {
                assertTrue(cli.diagnose(uri, source).any {
                    it.severity == DiagnosticSeverity.Error && "Outer.Inner" in it.message
                })
            }
            assertTrue(reached.get())
        }
    }

    @Test
    fun `workspace symbols cannot attribute a foreign fallback module to a malformed primary`(): Unit = runBlocking {
        val uri = root.resolve("Primary.groovy").toUri()
        val peerSource = "class Peer { void foreignMarker() {} }"
        val peer = Files.writeString(root.resolve("Peer.groovy"), peerSource)
        val source = "// foreignMarker is a comment, never a declaration\nclass Primary { void m( }"
        val reached = AtomicBoolean()
        withInvocationProof({ request, parsed ->
            assertEquals(uri, request.uri)
            assertTrue(peer in request.workspaceSources)
            assertNoPrimary(parsed)
            val foreign = assertNotNull(parsed.ast, "Probe precondition: no real fallback module was selected")
            assertTrue(parsed.compilationUnit.ast.modules.any { it === foreign })
            val input = assertNotNull(CompiledDocumentInput.of(foreign))
            assertEquals(peer.toUri(), input.uri)
            assertTrue(input.matchesCurrentText(null) {})
            assertTrue(foreign.classes.any { cls -> cls.methods.any { it.name == "foreignMarker" } })
            reached.set(true)
            parsed
        }) {
            compiler.updateWorkspaceModel(root, emptyList(), listOf(root))
            open(uri, source)
            compiler.compile(uri, source)
            assertTrue(reached.get(), "The compiler must have selected the physical peer as its fallback AST")
            try {
                val rows = workspace.symbol(WorkspaceSymbolParams("foreignMarker")).await().left
                assertTrue(rows.none { it.location.uri == uri.toString() }, "Foreign declaration attributed to $uri")
            } catch (error: ResponseErrorException) {
                assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code)
            }
        }
    }

    @Test
    fun `primary syntax diagnostics remain available when there is no module to own`(): Unit = runBlocking {
        val uri = root.resolve("Primary.groovy").toUri()
        val source = "class Primary { void m( }"
        val reached = AtomicBoolean()
        withInvocationProof({ _, parsed ->
            assertNoPrimary(parsed)
            assertNull(parsed.ast)
            assertTrue(parsed.compilationUnit.ast.modules.isEmpty())
            reached.set(true)
            parsed
        }) {
            open(uri, source)
            assertTrue(cli.diagnose(uri, source).any {
                it.severity == DiagnosticSeverity.Error && it.code?.left != "compilation-error"
            })
            assertTrue(reached.get())
        }
    }

    private fun assertNoPrimary(parsed: ParseResult) {
        assertTrue(parsed.compilationUnit.ast.modules.none { it.context === parsed.sourceUnit })
        assertTrue(parsed.diagnostics.any { it.severity == ParserSeverity.ERROR })
    }

    private fun filePath(name: String): Path =
        if (name.startsWith("file:")) Path.of(URI.create(name)) else Path.of(name)
}
