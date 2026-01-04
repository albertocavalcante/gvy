package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.MarkupKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class HoverContentGeneratorTest {

    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var generator: HoverContentGenerator

    @BeforeEach
    fun setup() {
        semanticResolver = mockk()
        generator = HoverContentGenerator(semanticResolver)
    }

    @Test
    fun `generateHover for VariableExpression returns formatted code`() {
        val varExpr = VariableExpression("testVar", ClassHelper.STRING_TYPE)

        // Mock semantic resolution
        every { semanticResolver.toSemanticType(ClassHelper.STRING_TYPE) } returns
            SemanticType.Known("java.lang.String")
        every { semanticResolver.formatSemanticType(any()) } returns "String"

        val result = generator.generateHover(varExpr)

        assertTrue(result.isSuccess)
        val hover = result.getOrNull()!!
        val contents = hover.contents.right

        assertEquals(MarkupKind.MARKDOWN, contents.kind)
        assertTrue(contents.value.contains("```groovy"))
        assertTrue(contents.value.contains("String testVar"))
    }

    @Test
    fun `generateHover for MethodNode returns details`() {
        val methodNode = MethodNode(
            "testMethod",
            Modifier.PUBLIC or Modifier.STATIC,
            ClassHelper.int_TYPE,
            arrayOf(Parameter(ClassHelper.STRING_TYPE, "arg1")),
            null,
            null,
        )

        val result = generator.generateHover(methodNode)

        assertTrue(result.isSuccess)
        val hover = result.getOrNull()!!
        val contents = hover.contents.right

        assertTrue(contents.value.contains("public static int testMethod(String arg1)"))
        assertTrue(contents.value.contains("**Return Type**: int"))
        assertTrue(contents.value.contains("**Modifiers**: public static"))
    }

    @Test
    fun `generateHover for ClassNode returns class structure`() {
        val classNode = ClassNode("com.test.MyClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        classNode.addField("myField", Modifier.PRIVATE, ClassHelper.STRING_TYPE, null)
        classNode.addMethod("myMethod", Modifier.PUBLIC, ClassHelper.VOID_TYPE, arrayOf<Parameter>(), null, null)

        val result = generator.generateHover(classNode)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("class MyClass"))
        assertTrue(contents.contains("- myMethod()"))
        assertTrue(contents.contains("- String myField"))
    }

    @Test
    fun `generateHover handles null type resolution gracefully`() {
        val varExpr = VariableExpression("unknownVar")

        every { semanticResolver.toSemanticType(any()) } returns SemanticType.Unknown("unknown")
        every { semanticResolver.formatSemanticType(any<SemanticType.Unknown>()) } returns "unresolved"

        val result = generator.generateHover(varExpr)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("def unknownVar"))
    }

    @Test
    fun `generateHover for DeclarationExpression returns variable declaration details`() {
        val varExpr = VariableExpression("myVar", ClassHelper.int_TYPE)
        val declExpr = org.codehaus.groovy.ast.expr.DeclarationExpression(
            varExpr,
            org.codehaus.groovy.syntax.Token.newSymbol("=", -1, -1),
            org.codehaus.groovy.ast.expr.ConstantExpression(42),
        )

        every { semanticResolver.toSemanticType(any()) } returns SemanticType.Primitive(PrimitiveKind.INT)
        every { semanticResolver.formatSemanticType(any()) } returns "int"

        val result = generator.generateHover(declExpr)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        // This is expected to FAIL if branch order is wrong (will show "### Binary Expression")
        assertTrue(
            contents.contains("### Variable Declaration"),
            "Expected Variable Declaration section but found: $contents",
        )
        assertTrue(contents.contains("int myVar"))
    }

    @Test
    fun `generateHover for BinaryExpression returns binary expression details`() {
        val left = VariableExpression("a", ClassHelper.int_TYPE)
        val right = VariableExpression("b", ClassHelper.int_TYPE)
        val binaryExpr = org.codehaus.groovy.ast.expr.BinaryExpression(
            left,
            org.codehaus.groovy.syntax.Token.newSymbol("+", -1, -1),
            right,
        )

        val result = generator.generateHover(binaryExpr)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("### Binary Expression"))
        assertTrue(contents.contains("**Operator**: +"))
    }

    @Test
    fun `generateHover for ImportNode returns import details`() {
        val importNode = org.codehaus.groovy.ast.ImportNode(ClassHelper.make("java.util.List"), "List")

        val result = generator.generateHover(importNode)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("### Import"))
        assertTrue(contents.contains("import java.util.List"))
        assertTrue(contents.contains("**Class**: java.util.List"))
    }
}
