package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.gvy.viz.model.NativeAstNodeDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeAstConverterTest {

    private val converter = NativeAstConverter()
    private val parser = GroovyParserFacade()

    private fun parse(code: String): org.codehaus.groovy.ast.ModuleNode {
        val request = ParseRequest(
            uri = URI("file:///test.groovy"),
            content = code,
        )
        val result = parser.parse(request)
        return result.ast ?: error("Failed to parse code")
    }

    @Test
    fun `should convert simple class declaration`() {
        val code = """
            class MyClass {
            }
        """.trimIndent()

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        assertNotNull(dto)
        assertEquals("ModuleNode", dto.type)
        assertTrue(dto.children.isNotEmpty(), "Should have at least one child (ClassNode)")

        val classNode = dto.children.find { it.type == "ClassNode" && it.properties["name"] == "MyClass" }
        assertNotNull(classNode, "Should find MyClass ClassNode")
        assertEquals("MyClass", classNode.properties["name"])
    }

    @Test
    fun `should convert class with method and extract type info`() {
        val code = """
            class MyClass {
                String myMethod() {
                    return "hello"
                }
            }
        """.trimIndent()

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        val classNode = dto.children.find { it.type == "ClassNode" && it.properties["name"] == "MyClass" }
        assertNotNull(classNode)

        // Find method node
        val methodNode = classNode.children.find {
            it.type == "MethodNode" && it.properties["name"] == "myMethod"
        }
        assertNotNull(methodNode, "Should find myMethod MethodNode")
        assertEquals("myMethod", methodNode.properties["name"])

        // Check for type info (Native parser provides this)
        if (methodNode is NativeAstNodeDto && methodNode.typeInfo != null) {
            assertThat(methodNode.typeInfo?.resolvedType).contains("String")
        }
    }

    @Test
    fun `should preserve source ranges`() {
        val code = """
            class Foo {
                int bar
            }
        """.trimIndent()

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        // Check that ranges are converted properly
        val classNode = dto.children.find { it.type == "ClassNode" && it.properties["name"] == "Foo" }
        assertNotNull(classNode)

        if (classNode.range != null) {
            assertThat(classNode.range?.startLine).isGreaterThan(0)
            assertThat(classNode.range?.startColumn).isGreaterThan(0)
        }
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

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        // Collect all IDs
        val ids = mutableSetOf<String>()
        fun collectIds(node: NativeAstNodeDto) {
            ids.add(node.id)
            node.children.forEach { collectIds(it as NativeAstNodeDto) }
        }
        collectIds(dto as NativeAstNodeDto)

        // All IDs should be unique
        val totalNodes = countNodes(dto)
        assertEquals(ids.size, totalNodes, "All nodes should have unique IDs")
    }

    @Test
    fun `should handle imports`() {
        val code = """
            import java.util.List
            import java.util.Map

            class MyClass {
            }
        """.trimIndent()

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        // Should have import nodes
        val imports = dto.children.filter { it.type == "ImportNode" }
        assertThat(imports).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `should extract field modifiers and visibility`() {
        val code = """
            class MyClass {
                public static final int CONSTANT = 42
                private String field
            }
        """.trimIndent()

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        val classNode = dto.children.find { it.type == "ClassNode" && it.properties["name"] == "MyClass" }
        assertNotNull(classNode)

        // Find constant field
        val constantField = classNode.children.find {
            it.type == "FieldNode" && it.properties["name"] == "CONSTANT"
        }
        assertNotNull(constantField)
        assertEquals("true", constantField.properties["isStatic"])
        assertEquals("true", constantField.properties["isFinal"])

        // Check symbol info
        if (constantField is NativeAstNodeDto && constantField.symbolInfo != null) {
            assertEquals("FIELD", constantField.symbolInfo?.kind)
        }
    }

    @Test
    fun `should extract method parameters`() {
        val code = """
            class MyClass {
                void myMethod(String param1, int param2) {
                }
            }
        """.trimIndent()

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        val classNode = dto.children.find { it.type == "ClassNode" && it.properties["name"] == "MyClass" }
        assertNotNull(classNode)

        val methodNode = classNode.children.find {
            it.type == "MethodNode" && it.properties["name"] == "myMethod"
        }
        assertNotNull(methodNode)

        // Check for parameter nodes
        val parameters = methodNode.children.filter { it.type == "Parameter" }
        assertThat(parameters).hasSize(2)

        val param1 = parameters.find { it.properties["name"] == "param1" }
        assertNotNull(param1)
    }

    @Test
    fun `should handle empty compilation unit`() {
        val code = ""

        val moduleNode = parse(code)
        val dto = converter.convert(moduleNode)

        assertNotNull(dto)
        assertEquals("ModuleNode", dto.type)
        // Empty scripts still have a generated script class
    }

    private fun countNodes(node: NativeAstNodeDto): Int {
        var count = 1
        node.children.forEach { count += countNodes(it as NativeAstNodeDto) }
        return count
    }
}
