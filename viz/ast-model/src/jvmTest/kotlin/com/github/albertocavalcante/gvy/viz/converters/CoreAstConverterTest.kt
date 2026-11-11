package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.TypeDeclaration
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

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
        val dto = converter.convert(compilationUnit, code)

        val classNode = dto.children.first()
        val methodNode = classNode.children.find { it.type == "MethodDeclaration" }
        assertNotNull(methodNode)

        // Should have isStatic property
        assertEquals("true", methodNode.properties["isStatic"])
    }

    @Test
    fun `expression metadata distinguishes operators names and unary forms`() {
        val code = """
            class Example {
                def work(obj, x) {
                    1 + 2
                    1 * 2
                    ++x
                    x++
                    foo()
                    bar()
                    obj.left
                    obj.right
                    outer(inner())
                    obj?.safe()
                }
            }
        """.trimIndent()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)
        val source = result.result.get()
        val dto = converter.convert(source, code)
        var nextId = 0
        fun assertStructure(raw: Node, converted: AstNodeDto) {
            assertEquals("node-${++nextId}", converted.id)
            assertEquals(raw.javaClass.simpleName, converted.type)
            assertEquals(raw.range?.begin?.line, converted.range?.startLine)
            assertEquals(raw.range?.begin?.column, converted.range?.startColumn)
            assertEquals(raw.range?.end?.line, converted.range?.endLine)
            assertEquals(raw.range?.end?.column, converted.range?.endColumn)
            assertEquals(raw.getChildNodes().size, converted.children.size)
            raw.getChildNodes().zip(converted.children).forEach { (child, childDto) ->
                assertStructure(child, childDto)
            }
        }
        assertStructure(source, dto)
        fun flatten(node: AstNodeDto): List<AstNodeDto> = listOf(node) + node.children.flatMap(::flatten)
        val nodes = flatten(dto)
        fun values(type: String, key: String): List<String?> =
            nodes.filter { it.type == type }.map { it.properties[key] }

        assertThat(values("BinaryExpr", "operator")).contains("+", "*")
        val unary = nodes.filter { it.type == "UnaryExpr" && it.properties["operator"] == "++" }
        assertThat(unary.map { it.properties["isPrefix"] }).containsExactly("true", "false")
        assertThat(values("MethodCallExpr", "methodName")).contains("foo", "bar", "outer", "inner", "safe")
        assertThat(values("PropertyExpr", "propertyName")).contains("left", "right")
        val outer = nodes.single { it.type == "MethodCallExpr" && it.properties["methodName"] == "outer" }
        assertTrue(outer.children.any { it.type == "MethodCallExpr" && it.properties["methodName"] == "inner" })
        val json = Json.encodeToString<AstNodeDto>(dto)
        assertTrue(json.contains("\"operator\":\"+\""))
        assertTrue(json.contains("\"methodName\":\"foo\""))
        assertTrue(json.contains("\"propertyName\":\"left\""))
        assertEquals(nodes.size, nodes.map { it.id }.toSet().size)
        assertEquals(dto, Json.decodeFromString<AstNodeDto>(json))
    }

    @Test
    fun `supplementary characters invalidate ambiguous ranges but preserve safe source slices`() {
        val source = "class Example { String before; String emoji = '😀'; String after }\r\n" +
            "class Later {\r\n    String safe\r\n}"
        val parsed = parser.parse(source)
        assertTrue(parsed.isSuccessful)
        val dto = converter.convert(parsed.result.get(), source)
        val example = dto.children.single { it.type == "ClassDeclaration" && it.properties["name"] == "Example" }
        val later = dto.children.single { it.type == "ClassDeclaration" && it.properties["name"] == "Later" }
        fun field(node: com.github.albertocavalcante.gvy.viz.model.AstNodeDto, name: String) =
            node.children.single { it.type == "FieldDeclaration" && it.properties["name"] == name }
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
        val parsed = parser.parse("class Example {}")
        assertTrue(parsed.isSuccessful)
        val ast = parsed.result.get()
        val dto = converter.convert(ast)
        val javaMethod = CoreAstConverter::class.java.getMethod("convert", CompilationUnit::class.java)
        assertEquals(dto, javaMethod.invoke(converter, ast))
        assertEquals(null, dto.children.single { it.properties["name"] == "Example" }.range)
    }

    @Test
    fun `concurrent conversions keep node IDs unique within each tree`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val leaf = object : Node() {
            override fun getChildNodes(): List<Node> = emptyList()
        }
        val blockedType = object : TypeDeclaration("Blocked") {
            override fun getChildNodes(): List<Node> {
                entered.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                return listOf(leaf)
            }
        }
        val first = CompilationUnit().apply { addType(blockedType) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<AstNodeDto> { converter.convert(first) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            converter.convert(CompilationUnit())
            resume.countDown()
            val dto = future.get(10, TimeUnit.SECONDS)
            val ids = listOf(dto.id, dto.children.single().id, dto.children.single().children.single().id)
            assertEquals(ids.size, ids.toSet().size)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private fun countNodes(node: CoreAstNodeDto): Int {
        var count = 1
        node.children.forEach { count += countNodes(it as CoreAstNodeDto) }
        return count
    }
}
