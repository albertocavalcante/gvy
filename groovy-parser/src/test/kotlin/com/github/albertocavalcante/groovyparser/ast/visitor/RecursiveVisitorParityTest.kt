package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ASTNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Parity tests validating that [RecursiveAstVisitor] produces identical results to [NodeVisitorDelegate].
 *
 * These tests ensure the new recursive visitor maintains behavioral compatibility with the legacy
 * delegate visitor during the migration period. Both visitors run on the same code and their outputs
 * are compared for:
 *
 * - **Node Set Equality**: Both visitors track the exact same set of AST nodes
 * - **Parent Relationships**: Each node has the same parent in both visitors
 *
 * ## Known Acceptable Differences
 *
 * Some differences are intentional improvements in the recursive visitor:
 *
 * 1. **Script-Level Statement Parenting**: Recursive visitor correctly parents top-level statements
 *    to the script ClassNode, while delegate leaves them orphaned (null parent). The recursive
 *    behavior is more correct per Groovy's AST model.
 *
 * ## Test Coverage
 *
 * - Classes, fields, methods, and annotations
 * - Control flow constructs (if/switch/for/while/do-while/try-catch)
 * - Complex expressions (binary, ternary, GStrings, maps, lists)
 * - Nested annotations and default parameters
 *
 * @see RecursiveAstVisitor
 * @see NodeVisitorDelegate
 */
class RecursiveVisitorParityTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `recursive visitor matches delegate for class with fields methods and annotations`() {
        val uri = URI.create("file:///parity1.groovy")
        val code = """
            @Deprecated
            class Foo {
                def field1 = 42
                String field2 = "hello"

                def method(String arg1, int arg2) {
                    def local = { param -> println param }
                    if (arg1) {
                        println arg2
                    }
                    try {
                        throw new RuntimeException("boom")
                    } catch (Exception e) {
                        println e
                    }
                }
            }
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")
        assertNotNull(result.ast, "AST should be available")

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        delegateNodes.forEach { node ->
            val delegateParent = delegateParents[node]
            val recursiveParent = recursiveParents[node]
            // Script top-level statements may be parented by the synthetic script class in the recursive visitor;
            // the legacy delegate leaves them parentless. Skip this known discrepancy for now.
            if (
                node is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                recursiveParent is org.codehaus.groovy.ast.ClassNode &&
                (recursiveParent.isScript() || delegateParent == null)
            ) {
                return@forEach
            }
            assertEquals(
                delegateParent,
                recursiveParent,
                "Parent mismatch for ${node.describe()} (delegate=${delegateParent.describe()}, recursive=${recursiveParent.describe()})",
            )
        }
    }

    @Test
    fun `recursive visitor matches delegate for control flow constructs`() {
        val uri = URI.create("file:///parity2.groovy")
        val code = """
            def check(x) {
                switch (x) {
                    case 1:
                        break
                    default:
                        println x
                }
                for (i in 0..1) {
                    continue
                }
                while (false) {
                    break
                }
                do {
                    println "loop"
                } while (false)
            }
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertNotNull(result.ast, "AST should be available")
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        delegateNodes.forEach { node ->
            val delegateParent = delegateParents[node]
            val recursiveParent = recursiveParents[node]
            if (
                node is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                recursiveParent is org.codehaus.groovy.ast.ClassNode &&
                (recursiveParent.isScript() || delegateParent == null)
            ) {
                return@forEach
            }
            assertEquals(
                delegateParent,
                recursiveParent,
                "Parent mismatch for ${node.describe()} (delegate=${delegateParent.describe()}, recursive=${recursiveParent.describe()})",
            )
        }
    }

    @Test
    fun `recursive visitor matches delegate for parameter and field annotations`() {
        val uri = URI.create("file:///parity-annotations.groovy")
        val code = """
            class Annotated {
                @Deprecated(since = "2.0")
                String field

                def run(
                    @SuppressWarnings(["unchecked"])
                    List<String> values
                ) {
                    values.each { v ->
                        @SuppressWarnings("rawtypes")
                        def local = v
                        return local
                    }
                }
            }
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertNotNull(result.ast, "AST should be available")

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        delegateNodes.forEach { node ->
            val delegateParent = delegateParents[node]
            val recursiveParent = recursiveParents[node]
            if (
                node is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                recursiveParent is org.codehaus.groovy.ast.ClassNode &&
                (recursiveParent.isScript() || delegateParent == null)
            ) {
                return@forEach
            }
            assertEquals(
                delegateParent,
                recursiveParent,
                "Parent mismatch for ${node.describe()} (delegate=${delegateParent.describe()}, recursive=${recursiveParent.describe()})",
            )
        }
    }

    @Test
    fun `recursive visitor matches delegate for nested annotations and default params`() {
        val uri = URI.create("file:///parity-nested-annotations.groovy")
        val code = """
            @interface Inner { String name() }
            @interface Wrapper { Inner value() }

            @Wrapper(@Inner(name = "top"))
            class AnnotatedDefaults {
                @Deprecated(since = "1.1")
                String fieldWithAnno = "x"

                def run(
                    @SuppressWarnings(["unused"])
                    String arg = "default"
                ) {
                    @Wrapper(@Inner(name = "local"))
                    def local = arg
                    return local
                }
            }
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")
        assertNotNull(result.ast, "AST should be available")

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        delegateNodes.forEach { node ->
            val delegateParent = delegateParents[node]
            val recursiveParent = recursiveParents[node]
            assertEquals(
                delegateParent,
                recursiveParent,
                "Parent mismatch for ${node.describe()} (delegate=${delegateParent.describe()}, recursive=${recursiveParent.describe()})",
            )
        }
    }

    @Test
    fun `recursive visitor matches delegate for complex declaration expressions`() {
        val uri = URI.create("file:///parity-declarations.groovy")
        val code = """
            def a = 1 + 2 * 3
            def b = a > 0 ? "${'$'}{a}-ok" : "bad"
            def c = [key: a, list: [1, 2, 3]]
            def d = "${'$'}a-${'$'}{b.toUpperCase()}"
        """.trimIndent()

        val result = fixture.parse(code, uri.toString())
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")
        assertNotNull(result.ast, "AST should be available")

        val (delegateNodes, delegateParents) = collectWithDelegate(result)
        val (recursiveNodes, recursiveParents) = collectWithRecursive(result, uri)

        assertNodeSetsMatch(delegateNodes, recursiveNodes)
        delegateNodes.forEach { node ->
            val delegateParent = delegateParents[node]
            val recursiveParent = recursiveParents[node]
            // Script top-level statements may be parented by the synthetic script class in the recursive visitor;
            // the legacy delegate leaves them parentless. Skip this known discrepancy for now.
            if (
                node is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                recursiveParent is org.codehaus.groovy.ast.ClassNode &&
                (recursiveParent.isScript() || delegateParent == null)
            ) {
                return@forEach
            }
            assertEquals(
                delegateParent,
                recursiveParent,
                "Parent mismatch for ${node.describe()} (delegate=${delegateParent.describe()}, recursive=${recursiveParent.describe()})",
            )
        }
    }

    private fun collectWithDelegate(
        result: com.github.albertocavalcante.groovyparser.api.ParseResult,
    ): Pair<Set<ASTNode>, Map<ASTNode, ASTNode?>> {
        val visitor = result.astVisitor!!
        val nodes = visitor.getAllNodes().toSet()
        val parents = nodes.associateWith { visitor.getParent(it) }
        return nodes to parents
    }

    private fun collectWithRecursive(
        result: com.github.albertocavalcante.groovyparser.api.ParseResult,
        uri: URI,
    ): Pair<Set<ASTNode>, Map<ASTNode, ASTNode?>> {
        val tracker = NodeRelationshipTracker()
        val recursiveVisitor = RecursiveAstVisitor(tracker)
        recursiveVisitor.visitModule(result.ast!!, uri)
        val nodes = tracker.getAllNodes().toSet()
        val parents = nodes.associateWith { tracker.getParent(it) }
        return nodes to parents
    }

    private fun assertNodeSetsMatch(delegateNodes: Set<ASTNode>, recursiveNodes: Set<ASTNode>) {
        val missing = delegateNodes - recursiveNodes
        val extra = recursiveNodes - delegateNodes
        assertEquals(emptySet<ASTNode>(), missing, "Recursive visitor is missing nodes: ${missing.describe()}")
        assertEquals(emptySet<ASTNode>(), extra, "Recursive visitor has extra nodes: ${extra.describe()}")
    }

    private fun Set<ASTNode>.describe(): String =
        joinToString(prefix = "[", postfix = "]") { "${it.javaClass.simpleName}@${it.lineNumber}:${it.columnNumber}" }

    private fun ASTNode?.describe(): String =
        this?.let { "${it.javaClass.simpleName}@${it.lineNumber}:${it.columnNumber}" }
            ?: "null"
}
