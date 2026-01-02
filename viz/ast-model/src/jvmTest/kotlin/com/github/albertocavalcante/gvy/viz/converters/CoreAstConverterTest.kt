package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreAstConverterTest {

    private val converter = CoreAstConverter()
    private val parser = GroovyParser()

    @Test
    fun `should convert simple class declaration`() {
        val code = """
            class MyClass {
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful, "Parse should succeed")

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        assertNotNull(dto)
        assertEquals("CompilationUnit", dto.type)
        assertTrue(dto.children.isNotEmpty(), "Should have at least one child (ClassDeclaration)")

        val classNode = dto.children.first()
        assertEquals("ClassDeclaration", classNode.type)
        assertEquals("MyClass", classNode.properties["name"])
    }

    @Test
    fun `should convert class with method`() {
        val code = """
            class MyClass {
                def myMethod() {
                    return 42
                }
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        val classNode = dto.children.first()
        assertThat(classNode.children).isNotEmpty

        // Find method declaration
        val methodNode = classNode.children.find { it.type == "MethodDeclaration" }
        assertNotNull(methodNode)
        assertEquals("myMethod", methodNode.properties["name"])
    }

    @Test
    fun `should preserve source ranges`() {
        val code = """
            class Foo {
                int bar
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        // Check that ranges are converted properly
        val classNode = dto.children.first()
        if (classNode.range != null) {
            assertThat(classNode.range?.startLine).isGreaterThan(0)
            assertThat(classNode.range?.startColumn).isGreaterThan(0)
        }
    }

    @Test
    fun `should handle empty compilation unit`() {
        val code = ""

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        assertNotNull(dto)
        assertEquals("CompilationUnit", dto.type)
        // Empty compilation units might have an auto-generated script class, so just check it exists
    }

    @Test
    fun `should generate unique IDs for each node`() {
        val code = """
            class MyClass {
                int field1
                int field2
                def method1() {}
                def method2() {}
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        // Collect all IDs
        val ids = mutableSetOf<String>()
        fun collectIds(node: CoreAstNodeDto) {
            ids.add(node.id)
            node.children.forEach { collectIds(it as CoreAstNodeDto) }
        }
        collectIds(dto as CoreAstNodeDto)

        // All IDs should be unique
        val totalNodes = countNodes(dto)
        assertEquals(ids.size, totalNodes, "All nodes should have unique IDs")
    }

    @Test
    fun `should handle package declaration`() {
        val code = """
            package com.example.test

            class MyClass {
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        // Check for package in properties or children
        val hasPackageInfo = dto.properties.containsKey("package") ||
            dto.children.any { it.type == "PackageDeclaration" }
        assertTrue(hasPackageInfo, "Should include package information")
    }

    @Test
    fun `should handle imports`() {
        val code = """
            import java.util.List
            import java.util.Map

            class MyClass {
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        // Should have import declarations
        val imports = dto.children.filter { it.type == "ImportDeclaration" }
        assertThat(imports).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `should extract method modifiers`() {
        val code = """
            class MyClass {
                static def myMethod() {
                }
            }
        """.trimIndent()

        val parseResult = parser.parse(code)
        assertTrue(parseResult.isSuccessful)

        val compilationUnit = parseResult.result.get()
        val dto = converter.convert(compilationUnit)

        val classNode = dto.children.first()
        val methodNode = classNode.children.find { it.type == "MethodDeclaration" }
        assertNotNull(methodNode)

        // Should have isStatic property
        assertEquals("true", methodNode.properties["isStatic"])
    }

    private fun countNodes(node: CoreAstNodeDto): Int {
        var count = 1
        node.children.forEach { count += countNodes(it as CoreAstNodeDto) }
        return count
    }
}
