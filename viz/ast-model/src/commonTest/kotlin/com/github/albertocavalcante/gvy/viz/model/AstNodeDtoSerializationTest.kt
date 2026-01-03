package com.github.albertocavalcante.gvy.viz.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AstNodeDtoSerializationTest {

    private val json = Json {
        prettyPrint = true
        classDiscriminator = "_type"
    }

    @Test
    fun `should serialize and deserialize RangeDto`() {
        val range = RangeDto(
            startLine = 1,
            startColumn = 5,
            endLine = 3,
            endColumn = 10,
        )

        val encoded = json.encodeToString(range)
        val decoded = json.decodeFromString<RangeDto>(encoded)

        assertEquals(range, decoded)
    }

    @Test
    fun `should serialize and deserialize CoreAstNodeDto`() {
        val node = CoreAstNodeDto(
            id = "node-1",
            type = "ClassDeclaration",
            range = RangeDto(1, 1, 10, 1),
            children = listOf(
                CoreAstNodeDto(
                    id = "node-2",
                    type = "MethodDeclaration",
                    range = RangeDto(3, 5, 7, 5),
                    children = emptyList(),
                    properties = mapOf("name" to "foo", "modifiers" to "public"),
                ),
            ),
            properties = mapOf("name" to "MyClass", "kind" to "CLASS"),
        )

        val encoded = json.encodeToString<AstNodeDto>(node)
        val decoded = json.decodeFromString<AstNodeDto>(encoded)

        assertEquals(node, decoded)
    }

    @Test
    fun `should serialize and deserialize NativeAstNodeDto without optional fields`() {
        val node = NativeAstNodeDto(
            id = "node-1",
            type = "ClassNode",
            range = null,
            children = emptyList(),
            properties = mapOf("name" to "MyClass"),
            symbolInfo = null,
            typeInfo = null,
        )

        val encoded = json.encodeToString<AstNodeDto>(node)
        val decoded = json.decodeFromString<AstNodeDto>(encoded)

        assertEquals(node, decoded)
    }

    @Test
    fun `should serialize and deserialize NativeAstNodeDto with symbol and type info`() {
        val node = NativeAstNodeDto(
            id = "node-1",
            type = "MethodNode",
            range = RangeDto(5, 1, 10, 5),
            children = emptyList(),
            properties = mapOf("name" to "calculateTotal"),
            symbolInfo = SymbolInfoDto(
                kind = "METHOD",
                scope = "CLASS",
                visibility = "PUBLIC",
            ),
            typeInfo = TypeInfoDto(
                resolvedType = "java.math.BigDecimal",
                isInferred = false,
                typeParameters = emptyList(),
            ),
        )

        val encoded = json.encodeToString<AstNodeDto>(node)
        val decoded = json.decodeFromString<AstNodeDto>(encoded)

        assertEquals(node, decoded)
    }

    @Test
    fun `should handle nested children in serialization`() {
        val leaf = CoreAstNodeDto(
            id = "leaf",
            type = "IntegerLiteral",
            range = null,
            children = emptyList(),
            properties = mapOf("value" to "42"),
        )

        val parent = CoreAstNodeDto(
            id = "parent",
            type = "BinaryExpression",
            range = null,
            children = listOf(leaf, leaf),
            properties = mapOf("operator" to "+"),
        )

        val root = CoreAstNodeDto(
            id = "root",
            type = "ExpressionStatement",
            range = null,
            children = listOf(parent),
            properties = emptyMap(),
        )

        val encoded = json.encodeToString<AstNodeDto>(root)
        val decoded = json.decodeFromString<AstNodeDto>(encoded)

        assertEquals(root, decoded)
    }

    @Test
    fun `should preserve empty collections in serialization`() {
        val node = NativeAstNodeDto(
            id = "node-1",
            type = "EmptyNode",
            range = null,
            children = emptyList(),
            properties = emptyMap(),
            symbolInfo = null,
            typeInfo = TypeInfoDto(
                resolvedType = "void",
                isInferred = true,
                typeParameters = emptyList(),
            ),
        )

        val encoded = json.encodeToString<AstNodeDto>(node)
        val decoded = json.decodeFromString<AstNodeDto>(encoded)

        assertEquals(node, decoded)
    }
}
