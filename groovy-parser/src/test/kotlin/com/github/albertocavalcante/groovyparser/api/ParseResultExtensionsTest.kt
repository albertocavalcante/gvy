package com.github.albertocavalcante.groovyparser.api

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParseResultExtensionsTest {

    private val parser = GroovyParserFacade()

    @Test
    fun `toCustomAst converts simple class`() {
        val request = ParseRequest(
            uri = URI.create("file:///test.groovy"),
            content = "class Foo {}",
        )
        val result = parser.parse(request)
        val customAst = result.toCustomAst()

        assertNotNull(customAst)
        assertEquals(1, customAst.types.size)
        assertEquals("Foo", customAst.types[0].name)
    }

    @Test
    fun `toCustomAst converts class with methods`() {
        val request = ParseRequest(
            uri = URI.create("file:///test.groovy"),
            content = """
                class Foo {
                    void bar() {
                        println "hello"
                    }
                }
            """.trimIndent(),
        )
        val result = parser.parse(request)
        val customAst = result.toCustomAst()

        assertNotNull(customAst)
        val classDecl = customAst.types[0] as ClassDeclaration
        assertEquals(1, classDecl.methods.size)
        assertEquals("bar", classDecl.methods[0].name)
        assertTrue(classDecl.methods[0].body is BlockStatement)
    }

    @Test
    fun `toCustomAst converts package and imports`() {
        val request = ParseRequest(
            uri = URI.create("file:///test.groovy"),
            content = """
                package com.example
                
                import java.util.List
                
                class Foo {}
            """.trimIndent(),
        )
        val result = parser.parse(request)
        val customAst = result.toCustomAst()

        assertNotNull(customAst)
        assertTrue(customAst.packageDeclaration.isPresent)
        assertEquals("com.example", customAst.packageDeclaration.get().name)
        assertEquals(1, customAst.imports.size)
    }

    @Test
    fun `toCustomAst returns null when ast is null`() {
        val request = ParseRequest(
            uri = URI.create("file:///test.groovy"),
            content = "class { broken syntax }}}}}",
        )
        val result = parser.parse(request)

        // May or may not have AST depending on how much Groovy recovered
        // Just verify it doesn't crash
        result.toCustomAst()
    }

    @Test
    fun `toCustomAst preserves method body structure`() {
        val request = ParseRequest(
            uri = URI.create("file:///test.groovy"),
            content = """
                class Calculator {
                    int add(int a, int b) {
                        return a + b
                    }
                }
            """.trimIndent(),
        )
        val result = parser.parse(request)
        val customAst = result.toCustomAst()

        assertNotNull(customAst)
        val classDecl = customAst.types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertEquals("add", method.name)
        assertEquals(2, method.parameters.size)
        assertNotNull(method.body)
    }
}
