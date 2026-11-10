package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fully qualified import of a default class (`import groovy.lang.Script`) resolves to the JVM-wide cached
 * ClassHelper node, and the parser stamps the import's position onto that shared node. Every script class
 * extends it, so a position index that tracks it answers for that line/column range in every other file.
 */
class SharedClassNodePositionTest {
    private val fixture = ParserTestFixture()

    private companion object {
        const val PHASE = Phases.SEMANTIC_ANALYSIS
    }

    @Test
    fun `an import of groovy lang Script in one file does not hijack a call in another`() {
        // Line 4, columns 9-34, as in the Jenkins stub that exposed it (#1354)
        fixture.parse("// a\n// b\n// c\n        import groovy.lang.Script\nclass A {}\n", "file:///A.groovy", PHASE)

        val b = "pipeline {\n    options {\n    }\n        timeout(time: 1, unit: 'HOURS')\n}\n"
        val node = nodeAt(b, "file:///B.groovy", line = 3, character = 10)

        assertNotShared(node, ClassHelper.SCRIPT_TYPE)
        assertTrue(node is MethodCallExpression && node.methodAsString == "timeout", "expected the call, got $node")
    }

    @Test
    fun `an import of groovy lang Closure in one file does not hijack another file`() {
        fixture.parse("// a\n// b\n// c\n        import groovy.lang.Closure\nclass A {}\n", "file:///A2.groovy", PHASE)

        val b = "class B {\n    def run(Closure body) {\n    }\n        Closure held = { -> 1 }\n}\n"
        val node = nodeAt(b, "file:///B2.groovy", line = 3, character = 10)

        assertNotShared(node, ClassHelper.CLOSURE_TYPE)
    }

    @Test
    fun `a class the document declares still resolves`() {
        val source = "class Base {}\nclass Child extends Base {}\n"
        val model = fixture.parse(source, "file:///Own.groovy", PHASE).astModel
        val uri = URI.create("file:///Own.groovy")

        assertEquals("Base", (model.getNodeAt(uri, 0, 7) as? ClassNode)?.name)
        assertEquals("Child", (model.getNodeAt(uri, 1, 7) as? ClassNode)?.name)
    }

    private fun nodeAt(source: String, uri: String, line: Int, character: Int) =
        fixture.parse(source, uri, PHASE).astModel.getNodeAt(URI.create(uri), line, character)

    private fun assertNotShared(node: Any?, shared: ClassNode) {
        assertFalse(node === shared, "resolved to the JVM-wide ${shared.name} node stamped by another file")
    }

    @Test
    fun `the shared default-class node never belongs to a compile unit`() {
        fixture.parse("import groovy.lang.Closure\nclass C {}\n", "file:///C.groovy", PHASE)

        assertNull(ClassHelper.CLOSURE_TYPE.compileUnit, "a compile unit leaked into the JVM-wide node")
        assertNull(ClassHelper.CLOSURE_TYPE.module, "a module leaked into the JVM-wide node")
    }
}
