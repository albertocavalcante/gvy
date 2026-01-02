package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Behavior tests validating RecursiveAstVisitor traversal and parent tracking.
 */
class RecursiveVisitorTraversalTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `tracks class members annotations and try catch`() {
        val uri = URI.create("file:///traversal-class.groovy")
        val code = """
            @Deprecated
            class Foo {
                def field1 = 42
                String field2 = "hello"

                def method(String arg1, int arg2) {
                    def local = { param -> println param }
                    def msg = "${'$'}{arg1}-ok"
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

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        val classNode = allNodes.filterIsInstance<ClassNode>().find { it.name.endsWith("Foo") }
        assertNotNull(classNode, "Should track Foo class")

        val annotation = allNodes.filterIsInstance<AnnotationNode>()
            .find { it.classNode?.name == "java.lang.Deprecated" }
        assertNotNull(annotation, "Should track @Deprecated annotation")
        assertNotNull(astModel.getParent(annotation!!), "Annotation should have a parent")

        val tryCatch = allNodes.filterIsInstance<TryCatchStatement>().firstOrNull()
        assertNotNull(tryCatch, "Should track TryCatchStatement")
        assertNotNull(astModel.getParent(tryCatch!!), "TryCatchStatement should have a parent")

        val gstring = allNodes.filterIsInstance<GStringExpression>().firstOrNull()
        assertNotNull(gstring, "Should track GStringExpression")
    }

    @Test
    fun `tracks control flow constructs`() {
        val uri = URI.create("file:///traversal-control.groovy")
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
        assertTrue(result.isSuccessful, "Diagnostics: ${result.diagnostics}")
        assertNotNull(result.ast, "AST should be available")

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        val switches = allNodes.filterIsInstance<SwitchStatement>()
        assertTrue(switches.isNotEmpty(), "Should track SwitchStatement")

        val cases = allNodes.filterIsInstance<CaseStatement>()
        assertTrue(cases.isNotEmpty(), "Should track CaseStatement")
        cases.forEach { assertNotNull(astModel.getParent(it), "CaseStatement should have a parent") }

        val breaks = allNodes.filterIsInstance<BreakStatement>()
        assertTrue(breaks.isNotEmpty(), "Should track BreakStatement")
        breaks.forEach { assertNotNull(astModel.getParent(it), "BreakStatement should have a parent") }

        val forLoops = allNodes.filterIsInstance<ForStatement>()
        assertTrue(forLoops.isNotEmpty(), "Should track ForStatement")

        val whileLoops = allNodes.filterIsInstance<WhileStatement>()
        assertTrue(whileLoops.isNotEmpty(), "Should track WhileStatement")

        val doWhileLoops = allNodes.filterIsInstance<DoWhileStatement>()
        assertTrue(doWhileLoops.isNotEmpty(), "Should track DoWhileStatement")
    }

    @Test
    fun `tracks annotation members and default params`() {
        val uri = URI.create("file:///traversal-annotations.groovy")
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

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        val annotations = allNodes.filterIsInstance<AnnotationNode>()
        assertTrue(annotations.size >= 3, "Should track nested annotations")

        val memberLiteral = allNodes.filterIsInstance<ConstantExpression>()
            .find { it.value == "1.1" }
        assertNotNull(memberLiteral, "Should track annotation member value")
        assertNotNull(astModel.getParent(memberLiteral!!), "Annotation member should have a parent")
    }
}
