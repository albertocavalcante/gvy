// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import groovy.lang.GroovyShell
import groovy.lang.MissingMethodException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutgoingCallBindingTest {
    private val uri = URI.create("file:///OutgoingBinding.groovy")
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(documentProvider = documents)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )
    private var lines = emptyList<String>()
    private val parse get() = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.shutdown()
    }

    private fun open(vararg sourceLines: String) {
        lines = sourceLines.toList()
        val source = lines.joinToString("\n", postfix = "\n")
        documents.put(uri, source)
        runBlocking { compilation.compile(uri, source) }
    }

    private fun span(line: Int, text: String): Range {
        val start = lines[line].indexOf(text)
        require(start >= 0 && start == lines[line].lastIndexOf(text)) { "Expected one '$text' on line $line" }
        return Range(Position(line, start), Position(line, start + text.length))
    }

    private fun calls(name: String) = parse.astModel.getNodes(uri).filterIsInstance<MethodCallExpression>()
        .filter { it.methodAsString == name }.sortedBy { it.lineNumber }

    private fun prepare(range: Range) = service.prepareCallHierarchy(
        CallHierarchyPrepareParams(TextDocumentIdentifier(uri.toString()), range.start),
    ).get(10, TimeUnit.SECONDS)

    private fun outgoing(caller: Range): List<CallHierarchyOutgoingCall> {
        val item = prepare(caller).single()
        assertEquals(caller, item.selectionRange)
        assertEquals(uri.toString(), item.uri)
        assertEquals("caller", item.name)
        return service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item)).get(10, TimeUnit.SECONDS)
    }

    private fun assertLocal(call: CallHierarchyOutgoingCall, name: String, declaration: Range, use: Range) {
        assertEquals(uri.toString(), call.to.uri)
        assertEquals(name, call.to.name)
        assertEquals(declaration, call.to.selectionRange)
        assertEquals(listOf(use), call.fromRanges)
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `outgoing uses the selected overload for each argument list in either declaration order`(
        script: Boolean,
        reverse: Boolean,
    ) {
        val declarations = listOf("def target() { 'zero' }", "def target(String value) { 'one' }")
        val ordered = if (reverse) declarations.reversed() else declarations
        open(
            if (script) "// script" else "class Owner {",
            ordered[0],
            ordered[1],
            "def caller() {",
            "    target()",
            "    target('x')",
            "}",
            if (script) "caller()" else "}",
        )
        val owner = assertNotNull(parse.ast).classes.single { if (script) it.isScript else it.name == "Owner" }
        val methods = owner.getDeclaredMethods("target").sortedBy { it.parameters.size }
        assertEquals(listOf(0, 1), methods.map { it.parameters.size })
        assertEquals("java.lang.String", methods[1].parameters.single().type.redirect().name)
        val uses = listOf(span(4, "target()"), span(5, "target('x')"))
        val calls = calls("target")
        assertEquals(uses, calls.map { it.toLspRange() })
        assertTrue(calls.all { it.isImplicitThis })
        val arguments = calls.map { assertIs<TupleExpression>(it.arguments).expressions }
        assertTrue(arguments[0].isEmpty())
        assertEquals("x", assertIs<ConstantExpression>(arguments[1].single()).value)
        methods.forEach { assertEquals(uri, parse.astModel.getUri(it)) }

        val result = outgoing(span(3, "caller")).sortedBy { it.fromRanges.first().start.line }
        assertEquals(2, result.size, "each declaration must receive its own call: $result")
        for ((index, method) in methods.withIndex()) {
            val declaration = span(1 + ordered.indexOf(declarations[index]), "target")
            assertEquals(declaration.start.line + 1, method.lineNumber)
            assertLocal(result[index], "target", declaration, uses[index])
        }
    }

    enum class CompanionCall(val expression: String) {
        LOCAL_ONLY(""),
        UNKNOWN_DYNAMIC("value.unknownMethod()"),
        BINARY_STRING("'x'.toUpperCase()"),
    }

    @ParameterizedTest
    @EnumSource(CompanionCall::class)
    fun `outgoing preserves the local call and accounts for a known binary call`(companion: CompanionCall) {
        open("class Owner {", "def local() {}", "def caller(value) {", "    local()", companion.expression, "}", "}")
        val owner = assertNotNull(parse.ast).classes.single { it.name == "Owner" }
        val local = owner.getDeclaredMethods("local").single()
        val localCall = calls("local").single()
        assertEquals(span(3, "local()"), localCall.toLspRange())
        val id = SemanticDocumentBuilder.createMethodSymbolId(owner, local).id
        val document = assertNotNull(compilation.getWorkspaceSymbolIndex()?.getDocument(uri))
        val occurrence = document.findOccurrences(id).single { it.role == OccurrenceRole.CALL }
        val token = span(3, "local")
        assertEquals(token.start, Position(occurrence.range.startLine, occurrence.range.startColumn))
        assertEquals(token.end, Position(occurrence.range.endLine, occurrence.range.endColumn))
        when (companion) {
            CompanionCall.BINARY_STRING -> {
                val call = calls("toUpperCase").single()
                assertEquals(span(4, companion.expression), call.toLspRange())
                val receiver = assertIs<ConstantExpression>(call.objectExpression)
                assertEquals("x", receiver.value)
                val binary = receiver.type.redirect()
                assertEquals("java.lang.String", binary.name)
                assertTrue(binary.isResolved)
                assertFalse(binary.isPrimaryClassNode)
                val method = binary.getDeclaredMethods("toUpperCase").single { it.parameters.isEmpty() }
                assertEquals(String::class.java, method.declaringClass.typeClass)
                assertTrue(assertIs<TupleExpression>(call.arguments).expressions.isEmpty())
                assertTrue(parse.symbolTable.registry.findMethodDeclarations(uri, "toUpperCase").isEmpty())
            }
            CompanionCall.UNKNOWN_DYNAMIC -> {
                val call = calls("unknownMethod").single()
                val value = assertIs<VariableExpression>(call.objectExpression)
                val parameter = owner.getDeclaredMethods("caller").single().parameters.single()
                assertSame(parameter, value.accessedVariable)
                assertTrue(parameter.isDynamicTyped)
                assertNull(call.methodTarget)
            }
            CompanionCall.LOCAL_ONLY -> Unit
        }

        val result = try {
            outgoing(span(2, "caller"))
        } catch (failure: ExecutionException) {
            assertEquals(CompanionCall.BINARY_STRING, companion)
            val response = assertIs<ResponseErrorException>(failure.cause).responseError
            assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
            return
        }
        assertLocal(result.single { it.to.name == "local" }, "local", span(1, "local"), span(3, "local()"))
        if (companion == CompanionCall.BINARY_STRING) {
            assertEquals(2, result.size, "a local-only result loses the known String call")
            assertStringDeclaration(result.single { it.to.name == "toUpperCase" }, span(4, companion.expression))
        } else {
            assertEquals(1, result.size)
        }
    }

    private fun assertStringDeclaration(call: CallHierarchyOutgoingCall, use: Range) {
        assertEquals(listOf(use), call.fromRanges)
        val source = Path.of(URI.create(call.to.uri))
        assertNotEquals(uri, source.toUri())
        assertEquals("String.java", source.fileName.toString())
        val text = Files.readAllLines(source)
        assertTrue("package java.lang;" in text)
        val name = call.to.selectionRange
        val line = text[name.start.line]
        assertEquals(name.start.line, name.end.line)
        assertEquals("toUpperCase", line.substring(name.start.character, name.end.character))
        assertTrue(line.substring(0, name.start.character).trimEnd().endsWith("String"))
        assertTrue(line.substring(name.start.character).startsWith("toUpperCase()"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `script prepare checks the concrete argument against the only one argument overload`(applicable: Boolean) {
        val parameter = if (applicable) "String" else "java.time.Instant"
        val argument = if (applicable) "'x'" else "new java.lang.Object()"
        open("def target() { 'zero' }", "def target($parameter value) { 'one' }", "target($argument)")
        val script = assertNotNull(parse.ast).classes.single { it.isScript }
        val method = script.getDeclaredMethods("target").single { it.parameters.size == 1 }
        val call = calls("target").single()
        assertTrue(call.isImplicitThis)
        assertNull(call.methodTarget)
        assertNull(call.getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET))
        val actual = assertIs<TupleExpression>(call.arguments).expressions.single()
        val formal = method.parameters.single().type.redirect()
        val shell = GroovyShell()
        shell.classLoader.use {
            val fixture = shell.parse(lines.joinToString("\n"))
            if (applicable) {
                assertEquals("x", assertIs<ConstantExpression>(actual).value)
                assertEquals(String::class.java, formal.typeClass)
                assertEquals("one", fixture.run())
            } else {
                val concrete = assertIs<ConstructorCallExpression>(actual).type.redirect()
                assertEquals(Any::class.java, concrete.typeClass)
                assertEquals(java.time.Instant::class.java, formal.typeClass)
                val failure = assertFailsWith<MissingMethodException> { fixture.run() }
                assertEquals("target", failure.method)
                assertEquals<Class<*>>(Any::class.java, failure.arguments.single().javaClass)
            }
        }

        val prepared = prepare(span(2, "target"))
        if (applicable) {
            assertEquals(listOf(span(1, "target")), prepared.map { it.selectionRange })
            assertEquals(uri.toString(), prepared.single().uri)
        } else {
            assertTrue(prepared.isEmpty(), "Groovy rejected this call: $prepared")
        }
    }

    @Test
    fun `an empty list which dispatches to the zero argument script method cannot prepare the Instant overload`() {
        open(
            "def target() { 'zero' }",
            "def target(java.time.Instant value) { 'one' }",
            "target(new java.util.ArrayList())",
        )
        val call = calls("target").single()
        assertTrue(call.isImplicitThis)
        assertNull(call.methodTarget)
        assertNull(call.getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET))
        val argument = assertIs<TupleExpression>(call.arguments).expressions.single()
        val concrete = assertIs<ConstructorCallExpression>(argument).type.redirect()
        assertEquals(java.util.ArrayList::class.java, concrete.typeClass)
        val script = assertNotNull(parse.ast).classes.single { it.isScript }
        val typed = script.getDeclaredMethods("target").single { it.parameters.size == 1 }
        assertEquals(java.time.Instant::class.java, typed.parameters.single().type.redirect().typeClass)
        val shell = GroovyShell()
        shell.classLoader.use {
            assertEquals("zero", shell.parse(lines.joinToString("\n")).run())
        }
        val prepared = prepare(span(2, "target"))
        if (prepared.isNotEmpty()) {
            val item = prepared.single()
            assertEquals(uri.toString(), item.uri)
            assertEquals(span(0, "target"), item.selectionRange)
        }
    }
}
