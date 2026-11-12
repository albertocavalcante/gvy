// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
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

    @Test
    fun `an import reference keeps its own position when another file restamps the shared node`() {
        val source = "package p\n\nimport java.util.List\nclass Uses {}\n"
        val parsed = fixture.parse(source, "file:///Uses.groovy", PHASE)
        // Another compile stamps the shared java.util.List node between this file's compile and its visit
        fixture.parse("// x\n// y\n// z\n// w\n            import java.util.List\n", "file:///Other.groovy", PHASE)
        val uri = URI.create("file:///Uses.groovy")
        val model = RecursiveAstVisitor(NodeRelationshipTracker()).also { it.visitModule(parsed.ast!!, uri) }

        val reference = model.getNodeAt(uri, 2, 17) // LSP 2:17 lies in `List` of line 3
        assertTrue(reference is ClassNode && reference.name == "java.util.List", "import hover target: $reference")
        assertFalse(reference === ClassHelper.LIST_TYPE, "the shared node itself must not be indexed")
        assertEquals(3 to 8, reference.lineNumber to reference.columnNumber, "position from this file's import text")
        assertNull(model.getNodeAt(uri, 4, 14) as? ClassNode, "nothing at the other file's stamped position")
    }

    @Test
    fun `an aliased import of a shared type resolves at the imported name`() {
        val uri = "file:///Alias.groovy"
        // The reference spans the qualified name `java.util.List`, which starts at column 8
        val node = nodeAt("import java.util.List as Names\nclass Alias {}\n", uri, line = 0, character = 18)

        assertOwnReference(node, "java.util.List", line = 1, column = 8)
    }

    @Test
    fun `a static import of a shared type resolves at the imported class name`() {
        val uri = "file:///Static.groovy"
        // The reference spans the qualified class name `java.lang.String`, which starts at column 15
        val node = nodeAt("import static java.lang.String.valueOf\nclass Static {}\n", uri, line = 0, character = 26)

        assertOwnReference(node, "java.lang.String", line = 1, column = 15)
    }

    @Test
    fun `a supplementary character earlier on the import line keeps the parser's column convention`() {
        val uri = "file:///Emoji.groovy"
        // The emoji is one code point but two UTF-16 units; parser columns count code points
        val source = "/* \uD83D\uDE00 */ import java.util.List\nclass Emoji {}\n"
        val module = checkNotNull(fixture.parse(source, uri, PHASE).ast)
        val importColumn = module.imports.single().columnNumber
        val visitor = RecursiveAstVisitor(NodeRelationshipTracker()).also { it.visitModule(module, URI.create(uri)) }

        val node = visitor.getNodeAt(URI.create(uri), 0, importColumn - 1 + "import ".length)

        assertOwnReference(node, "java.util.List", line = 1, column = importColumn + "import ".length)
        assertEquals(importColumn + "import java.util.List".length, (node as ClassNode).lastColumnNumber)
    }

    private fun assertOwnReference(node: Any?, name: String, line: Int, column: Int) {
        assertTrue(node is ClassNode && node.name == name, "expected a $name reference, got $node")
        assertFalse(ClassHelper.isCachedType(node), "the shared node itself must not be indexed")
        assertEquals(line to column, node.lineNumber to node.columnNumber)
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
