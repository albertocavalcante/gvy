// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.viz.model.NativeAstNodeDto
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        val dto = converter.convert(moduleNode, code)

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
        val dto = converter.convert(moduleNode, code)

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
            assertThat(methodNode.typeInfo.resolvedType).contains("String")
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
        val dto = converter.convert(moduleNode, code)

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
        val dto = converter.convert(moduleNode, code)

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
        val dto = converter.convert(moduleNode, code)

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
        val dto = converter.convert(moduleNode, code)

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
            assertEquals("FIELD", constantField.symbolInfo.kind)
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
        val dto = converter.convert(moduleNode, code)

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
        val dto = converter.convert(moduleNode, code)

        assertNotNull(dto)
        assertEquals("ModuleNode", dto.type)
        // Empty scripts still have a generated script class
    }

    @Test
    fun `property visibility remains distinct from its backing field`() {
        val code = """
            class Example {
                String name
                static final String version = '1'
                private String hidden
                protected void work() {}
            }
        """.trimIndent()
        val dto = converter.convert(parse(code), code)
        val cls = dto.children.single { it.type == "ClassNode" && it.properties["name"] == "Example" }

        fun visibility(type: String, name: String): String {
            val node = cls.children.single { it.type == type && it.properties["name"] == name } as NativeAstNodeDto
            return assertNotNull(node.symbolInfo).visibility
        }

        assertEquals("PUBLIC", assertNotNull((cls as NativeAstNodeDto).symbolInfo).visibility)
        assertEquals("PUBLIC", visibility("PropertyNode", "name"))
        assertEquals("PRIVATE", visibility("FieldNode", "name"))
        assertEquals("PUBLIC", visibility("PropertyNode", "version"))
        assertEquals("PRIVATE", visibility("FieldNode", "hidden"))
        assertEquals("PROTECTED", visibility("MethodNode", "work"))
    }

    @Test
    fun `supplementary characters invalidate ambiguous ranges but preserve safe source slices`() {
        val source = "class Example { String before; String emoji = '😀'; String after }\r\n" +
            "class Later {\r\n    String safe\r\n}"
        val dto = converter.convert(parse(source), source)
        val example = dto.children.single { it.type == "ClassNode" && it.properties["name"] == "Example" }
        val later = dto.children.single { it.type == "ClassNode" && it.properties["name"] == "Later" }
        fun field(node: com.github.albertocavalcante.gvy.viz.model.AstNodeDto, name: String) =
            node.children.single { it.type == "FieldNode" && it.properties["name"] == name }
        fun slice(node: com.github.albertocavalcante.gvy.viz.model.AstNodeDto): String {
            val range = assertNotNull(node.range)
            val line = source.split("\r\n")[range.startLine - 1]
            assertEquals(range.startLine, range.endLine)
            return line.substring(range.startColumn - 1, range.endColumn - 1)
        }
        assertEquals("String before", slice(field(example, "before")))
        assertEquals(null, field(example, "emoji").range)
        assertEquals(null, field(example, "after").range)
        assertEquals(null, example.range)
        assertEquals("String safe", slice(field(later, "safe")))
    }

    @Test
    fun `conversion without source makes ranges unavailable`() {
        val ast = parse("class Example {}")
        val dto = converter.convert(ast)
        val javaMethod = NativeAstConverter::class.java.getMethod("convert", ModuleNode::class.java)
        assertEquals(dto, javaMethod.invoke(converter, ast))
        assertEquals(null, dto.children.single { it.properties["name"] == "Example" }.range)
    }

    @Test
    fun `concurrent conversions keep node IDs unique within each tree`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val blockedClass = object : ClassNode("Blocked", 0, ClassHelper.OBJECT_TYPE) {
            override fun getFields(): MutableList<FieldNode> {
                entered.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                return mutableListOf(FieldNode("value", 0, ClassHelper.OBJECT_TYPE, this, null))
            }
        }
        val first = object : ModuleNode(null as SourceUnit?) {
            override fun getClasses(): MutableList<ClassNode> = mutableListOf(blockedClass)
        }
        val empty = object : ModuleNode(null as SourceUnit?) {
            override fun getClasses(): MutableList<ClassNode> = mutableListOf()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<com.github.albertocavalcante.gvy.viz.model.AstNodeDto> {
                converter.convert(first)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            converter.convert(empty)
            resume.countDown()
            val dto = future.get(10, TimeUnit.SECONDS)
            val ids = listOf(dto.id, dto.children.single().id, dto.children.single().children.single().id)
            assertEquals(ids.size, ids.toSet().size)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private fun countNodes(node: NativeAstNodeDto): Int {
        var count = 1
        node.children.forEach { count += countNodes(it as NativeAstNodeDto) }
        return count
    }
}
