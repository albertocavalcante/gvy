package com.github.albertocavalcante.groovyparser.ast.symbols

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class SymbolQueryTest {

    @Test
    fun `test symbol query DSL`() {
        val content = """
            class MyClass {
                public String field1
                private int field2
                static void staticMethod() {}
                void instanceMethod() {}
            }
        """.trimIndent()
        val uri = URI.create("file:///test.groovy")
        val parser = GroovyParserFacade()
        val result = parser.parse(ParseRequest(uri, content))
        val index = SymbolIndex().buildFromVisitor(result.astModel)

        // Test name query
        val classSymbols = index.query {
            name = "MyClass"
            category = SymbolCategory.CLASS
        }
        assertEquals(1, classSymbols.size)
        assertEquals("MyClass", classSymbols.first().name)

        // Test static filter
        val staticMethods = index.query {
            category = SymbolCategory.METHOD
            isStatic = true
        }
        assertEquals(1, staticMethods.size)
        assertEquals("staticMethod", staticMethods.first().name)

        // Test visibility filter
        val privateFields = index.query {
            category = SymbolCategory.FIELD
            visibility = Visibility.PRIVATE
        }

        assertTrue(privateFields.any { it.name == "field2" }, "Should find field2")

        // We don't enforce exactly 1 because synthetic fields might exist
        val field2 = privateFields.find { it.name == "field2" }
        assertNotNull(field2, "Field2 should be found")
        assertEquals(Visibility.PRIVATE, (field2 as Symbol.Field).visibility)
    }
}
