// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
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
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeCallHierarchyBindingTest {
    private val uri = URI.create("file:///HierarchyBinding.groovy")
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(documentProvider = documents)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
    private val service = GroovyTextDocumentService(scope, compilation, options)
    private var lines = emptyList<String>()

    private val parse get() = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
    private val document get() = assertNotNull(compilation.getWorkspaceSymbolIndex()?.getDocument(uri))

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
        val column = lines[line].indexOf(text)
        require(column >= 0 && column == lines[line].lastIndexOf(text)) { "Expected one '$text' on line $line" }
        return Range(Position(line, column), Position(line, column + text.length))
    }

    private fun prepare(range: Range) = service.prepareCallHierarchy(
        CallHierarchyPrepareParams(TextDocumentIdentifier(uri.toString()), range.start),
    ).get(10, TimeUnit.SECONDS)

    private fun selected(range: Range, name: String): CallHierarchyItem {
        val item = prepare(range).single()
        assertEquals(uri.toString(), item.uri)
        assertEquals(range, item.selectionRange)
        assertEquals(name, item.name)
        return item
    }

    private fun indexedRanges(symbol: String, role: OccurrenceRole): List<Range> = document.findOccurrences(symbol)
        .filter { it.role == role }.map {
            val range = it.range
            Range(Position(range.startLine, range.startColumn), Position(range.endLine, range.endColumn))
        }.sortedWith(compareBy({ it.start.line }, { it.start.character }))

    private data class Caller(val name: String, val selection: Range, val uses: List<Range>)

    private fun incoming(item: CallHierarchyItem): List<Caller> =
        service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item)).get(10, TimeUnit.SECONDS)
            .map {
                assertEquals(uri.toString(), it.from.uri)
                Caller(it.from.name, it.from.selectionRange, it.fromRanges)
            }.sortedBy { it.selection.start.line }

    @Test
    fun `a superclass reference does not mask a constructor call to the same class`() {
        open("class Local {}", "class Child extends Local {}", "class Caller { def make() { new Local() } }")
        val local = assertNotNull(parse.ast).classes.single { it.name == "Local" }
        val header = span(1, "Local")
        val headerType = assertIs<ClassNode>(parse.astModel.getNodeAt(uri, 1, header.start.character))
        assertSame(local, headerType.redirect())
        assertEquals(listOf(header), indexedRanges("Local#", OccurrenceRole.TYPE_REF))
        assertEquals(listOf(span(2, "Local")), indexedRanges("Local#", OccurrenceRole.CALL))

        val item = selected(span(0, "Local"), "Local")
        assertEquals(listOf(Caller("make", span(2, "make"), listOf(span(2, "Local")))), incoming(item))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `the class and its explicit constructors have distinct incoming calls`(declarationLine: Int) {
        open(
            "class Local {",
            "    Local() {}",
            "    Local(String value) {}",
            "}",
            "class Caller {",
            "    def empty() { new Local() }",
            "    def named() { new Local('x') }",
            "}",
        )
        val local = assertNotNull(parse.ast).classes.single { it.name == "Local" }
        val constructions = parse.astModel.getNodes(uri).filterIsInstance<ConstructorCallExpression>()
            .filter { it.lineNumber in 6..7 }
        assertEquals(2, constructions.size)
        constructions.forEach { assertSame(local, it.type.redirect()) }
        val classCalls = listOf(span(5, "Local"), span(6, "Local"))
        assertEquals(classCalls, indexedRanges("Local#", OccurrenceRole.CALL))
        val constructors = local.declaredConstructors.sortedBy { it.lineNumber }
        assertEquals(listOf(2, 3), constructors.map { it.lineNumber })
        for ((index, constructor) in constructors.withIndex()) {
            val symbol = SemanticDocumentBuilder.createMethodSymbolId(local, constructor).id
            assertEquals(listOf(span(5 + index, "new Local")), indexedRanges(symbol, OccurrenceRole.CALL))
        }

        val selection = span(declarationLine, "Local")
        val node = parse.astModel.getNodeAt(uri, declarationLine, selection.start.character)
        if (declarationLine == 0) {
            assertSame(local, node)
        } else {
            assertSame(constructors[declarationLine - 1], assertIs<ConstructorNode>(node))
        }
        val callers = listOf(
            Caller("empty", span(5, "empty"), listOf(classCalls[0])),
            Caller("named", span(6, "named"), listOf(classCalls[1])),
        )
        val name = if (declarationLine == 0) "Local" else "<init>"
        val expected = if (declarationLine == 0) callers else listOf(callers[declarationLine - 1])
        assertEquals(expected, incoming(selected(selection, name)))
    }

    @Test
    fun `an indexed method call on a newly constructed local receiver has a caller`() {
        open("class Target { def work() {} }", "class Caller { def invoke() { new Target().work() } }")
        val target = assertNotNull(parse.ast).classes.single { it.name == "Target" }
        val method = target.getDeclaredMethods("work").single()
        val call = parse.astModel.getNodes(uri).filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "work" }
        val receiver = assertIs<ConstructorCallExpression>(call.objectExpression)
        assertFalse(receiver.isUsingAnonymousInnerClass)
        assertSame(target, receiver.type.redirect())
        assertNull(call.methodTarget)
        assertNull(call.getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET))
        val symbol = SemanticDocumentBuilder.createMethodSymbolId(target, method).id
        val use = span(1, "work")
        assertEquals(listOf(use), indexedRanges(symbol, OccurrenceRole.CALL))

        val item = selected(span(0, "work"), "work")
        assertEquals(listOf(Caller("invoke", span(1, "invoke"), listOf(use))), incoming(item))
    }

    @Test
    fun `an anonymous override is never reported as an invocation of the base method`() {
        open(
            "class Target { void work() {} }",
            "class Caller {",
            "    def plain(Target target) { target.work() }",
            "    def anonymous() { new Target() { void work() {} }.work() }",
            "}",
        )
        val classes = assertNotNull(parse.ast).classes
        val target = classes.single { it.name == "Target" }
        val subclass = classes.filterIsInstance<InnerClassNode>().single { it.isAnonymous }
        assertSame(target, subclass.superClass.redirect())
        assertSame(subclass, subclass.getDeclaredMethods("work").single().declaringClass)
        val call = parse.astModel.getNodes(uri).filterIsInstance<MethodCallExpression>()
            .single { it.lineNumber == 4 && it.methodAsString == "work" }
        assertTrue(assertIs<ConstructorCallExpression>(call.objectExpression).isUsingAnonymousInnerClass)
        val method = target.getDeclaredMethods("work").single()
        val symbol = SemanticDocumentBuilder.createMethodSymbolId(target, method).id
        val use = span(2, "work")
        assertEquals(listOf(use), indexedRanges(symbol, OccurrenceRole.CALL))

        val item = selected(span(0, "work"), "work")
        try {
            assertEquals(listOf(Caller("plain", span(2, "plain"), listOf(use))), incoming(item))
        } catch (failure: ExecutionException) {
            val response = assertIs<ResponseErrorException>(failure.cause).responseError
            assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
        }
    }

    @ParameterizedTest
    @CsvSource("false, 0", "false, 1", "true, 0", "true, 1")
    fun `prepare selects the script overload accepting the call in either declaration order`(
        reversed: Boolean,
        argumentCount: Int,
    ) {
        val definitions = listOf("def target() {}", "def target(String value) {}")
        val ordered = if (reversed) definitions.reversed() else definitions
        open(ordered[0], ordered[1], if (argumentCount == 0) "target()" else "target('x')")
        val call = parse.astModel.getNodes(uri).filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "target" }
        assertTrue(call.isImplicitThis)
        assertEquals(argumentCount, assertIs<TupleExpression>(call.arguments).expressions.size)
        val script = assertNotNull(parse.ast).classes.single { it.isScript }
        val method = script.getDeclaredMethods("target").single { it.parameters.size == argumentCount }
        val expected = span(ordered.indexOf(definitions[argumentCount]), "target")
        assertEquals(expected.start.line + 1, method.lineNumber)
        val use = span(2, "target")
        val leaf = assertNotNull(parse.astModel.getNodeAt(uri, 2, use.start.character))
        assertTrue(leaf === call || parse.astModel.getParent(leaf) === call)
        val resolved = leaf.resolveToDefinition(parse.astModel, parse.symbolTable, strict = false)
        val route = "Cursor ${leaf.javaClass.simpleName} resolved to ${resolved?.javaClass?.simpleName} " +
            "at line ${resolved?.lineNumber}"
        val prepared = prepare(use)
        assertEquals(listOf(expected), prepared.map { it.selectionRange }, route)
        assertEquals(uri.toString(), prepared.single().uri)
        assertEquals("target", prepared.single().name)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `prepare refuses script overloads without a proven target for a dynamic argument`(variableArity: Boolean) {
        val first = if (variableArity) "String value, String extra = 'x'" else "String value"
        val second = if (variableArity) "Integer value, Object... rest" else "Integer value"
        open("def target($first) {}", "def target($second) {}", "target(binding.getVariable('value'))")
        val call = parse.astModel.getNodes(uri).filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "target" }
        assertTrue(call.isImplicitThis)
        assertNull(call.methodTarget)
        assertNull(call.getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET))
        val use = span(2, "target")
        val calls = document.occurrences.filter { it.role == OccurrenceRole.CALL && it.range.startLine == 2 }
        assertFalse(calls.any { it.range.startColumn == use.start.character }, "Unexpected indexed target: $calls")
        assertEquals(emptyList(), prepare(use))
    }
}
