// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SignatureCompletionRequest
import com.github.albertocavalcante.gvy.gls.providers.SignatureHelpProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhysicalMemberSourceDeliveryTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @CsvSource(
        "original,stable", "original,replace", "recovery,stable", "recovery,replace",
        "completion,stable", "completion,replace", "completion,earlier", "empty,stable", "empty,earlier",
    )
    fun `actual physical member owner must survive final delivery`(route: String, finish: String): Unit = runBlocking {
        supervisorScope {
            val project = MemberProject(root, route, this)
            try {
                project.exercise(finish)
            } finally {
                coroutineContext.cancelChildren()
                project.compiler.shutdown()
            }
        }
    }
}

private class MemberProject(root: Path, private val route: String, scope: CoroutineScope) {
    private val roots = listOf("first", "last").map { Files.createDirectory(root.resolve(it)) }
    private val later = roots.last().resolve("External.groovy")
    private val earlier = roots.first().resolve("External.groovy")
    private val uri = root.resolve("Primary.groovy").toUri()
    private val called = if (route == "empty") ABSENT else MEMBER
    private val prefix = "class Primary { void use(External receiver) { receiver." +
        if (route == "completion") "only" else "$called("
    private val text = when (route) {
        "completion" -> "$prefix } }"
        "recovery" -> prefix
        else -> "$prefix'x') } }"
    }
    private val position = Position(0, prefix.length)
    private val parseText = when (route) {
        "completion" -> prefix + CompletionProvider.DUMMY_IDENTIFIER + " } }"
        "recovery" -> prefix + "null)}}"
        else -> text
    }
    private val documents = DocumentProvider()
    private val calls = mutableListOf<String>()
    private lateinit var memberLoader: GroovyClassLoader
    private lateinit var sourceLookup: NativeSourceMembership
    val compiler = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker ->
        object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                val parsed = worker.parse(request)
                var transferred = false
                try {
                    assertEquals(uri, request.uri)
                    assertEquals(roots, request.sourceRoots)
                    assertTrue(request.openSources.isEmpty() && request.workspaceSources.isEmpty())
                    if (route == "recovery" && request.content == text) {
                        assertTrue(assertNotNull(parsed.tokenIndex).hasCompleteLexicalCoverage)
                        assertNull(parsed.sourceUnit.ast, "Incomplete original must actually need recovery")
                    } else {
                        assertEquals(parseText, request.content)
                        sourceLookup = assertOwner(parsed, later, MEMBER, "String")
                        memberLoader = parsed.compilationUnit.classLoader
                        assertNotNull(memberLoader.getResource(PROBE))
                    }
                    calls.add(request.content)
                    transferred = true
                    return parsed
                } finally {
                    if (!transferred) parsed.compilationUnit.classLoader.close()
                }
            }
        }
    })
    private val service = GroovyTextDocumentService(
        scope,
        compiler,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    init {
        // Populate the model before the physical source exists so only the compiler's own search can supply it.
        compiler.updateWorkspaceModel(root, emptyList(), roots)
        Files.writeString(later, declaration(MEMBER, "String"))
        Files.writeString(roots.last().resolve(PROBE), "owned resource")
        compiler.onDocumentOpened(uri)
        documents.put(uri, text, DocumentVersion.of(1))
    }

    suspend fun exercise(finish: String) {
        val identifier = TextDocumentIdentifier(uri.toString())
        val labels = if (route == "completion") {
            service.completion(CompletionParams(identifier, position)).await().left
                .filter { it.kind == CompletionItemKind.Method && it.label == MEMBER }.map { it.detail }
        } else {
            service.signatureHelp(SignatureHelpParams(identifier, position)).await().signatures.map { it.label }
        }
        val expected = if (route == "empty") emptyList() else listOf("void $MEMBER(String value)")
        assertEquals(expected, labels, "Stable wire answer must come from External")
        assertEquals(if (route == "recovery") listOf(text, parseText) else listOf(parseText), calls)
        val request = SignatureCompletionRequest(compiler, documents, uri, position, completion = route == "completion")
        suspend fun deliver(): List<String> = request.deliver {
            val materialized = if (route == "completion") {
                CompletionProvider.getContextualCompletions(request)
                    .filter { it.kind == CompletionItemKind.Method && it.label == MEMBER }.map { it.detail }
            } else {
                SignatureHelpProvider.provideSignatureHelp(request).signatures.map { it.label }
            }
            assertEquals(expected, materialized, "Mutation must follow successful materialization")
            if (route == "completion" || route == "recovery") {
                assertNull(memberLoader.getResource(PROBE), "Only mutate after disposal of the derived loader")
            }
            if (route != "completion") assertEquals(1, calls.count { it == text }, "Reuse the actual warmed original")
            if (finish != "stable") rotate(finish)
            assertTrue(request.source.isCurrent() && request.source.workerCurrent())
            materialized
        }
        if (finish == "stable") {
            assertEquals(expected, deliver())
        } else {
            val error = assertFailsWith<ResponseErrorException> { deliver() }
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
    }

    private fun rotate(finish: String) {
        val selected = if (finish == "earlier") earlier else later
        val newName = when (route) {
            "empty" -> ABSENT
            "completion" -> ALTERNATE
            else -> MEMBER
        }
        val type = if (route == "completion") "String" else "Double"
        val changed = declaration(newName, type)
        val old = Files.readString(later)
        val stamp = Files.getLastModifiedTime(later)
        if (finish == "replace") assertEquals(old.length, changed.length) else assertFalse(Files.exists(earlier))
        Files.writeString(selected, changed)
        Files.setLastModifiedTime(selected, stamp)
        assertEquals(stamp, Files.getLastModifiedTime(selected))
        if (finish == "earlier") {
            assertEquals(old, Files.readString(later))
            assertEquals(NativeSourceMembership.Status.CHANGED, sourceLookup.check {})
        }
        val fresh = GroovyParserFacade().parse(
            ParseRequest(uri, parseText, sourceRoots = roots, compilePhase = Phases.CANONICALIZATION),
        )
        fresh.compilationUnit.classLoader.use { assertOwner(fresh, selected, newName, type) }
    }

    private fun assertOwner(parsed: ParseResult, selected: Path, method: String, type: String): NativeSourceMembership {
        val primary = assertNotNull(parsed.ast)
        assertSame(primary, parsed.sourceUnit.ast)
        val input = parsed.compilationUnit.ast.modules.single { CompiledDocumentInput.of(it)?.uri == selected.toUri() }
        val receipt = assertNotNull(CompiledDocumentInput.of(input))
        assertTrue(receipt.matchesCurrentText(null) {} && receipt.matchesText(declaration(method, type)))
        val owner = input.classes.single { it.name == "External" }
        val user = primary.classes.single { it.name == "Primary" }
        assertTrue(user.getDeclaredMethods(method).isEmpty())
        assertSame(owner, user.getDeclaredMethods("use").single().parameters.single().type.redirect())
        val declared = owner.getDeclaredMethods(method).single()
        assertSame(owner, declared.declaringClass)
        assertEquals("java.lang.$type", declared.parameters.single().type.name)
        if (route == "empty" && method != ABSENT) assertTrue(owner.getDeclaredMethods(ABSENT).isEmpty())
        return assertNotNull(NativeSourceMembership.of(input)).also {
            assertEquals(NativeSourceMembership.Status.CURRENT, it.check {})
        }
    }

    private fun declaration(name: String, type: String) = "class External { void $name($type value) {} }"

    private companion object {
        const val MEMBER = "onlyOnExternal"
        const val ALTERNATE = "elseOnExternal"
        const val ABSENT = "initiallyAbsent"
        const val PROBE = "gvy-physical-member-delivery-resource.txt"
    }
}
