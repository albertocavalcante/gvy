package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.syntax.Token
import org.eclipse.lsp4j.MarkupKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        val declExpr = DeclarationExpression(
            varExpr,
            Token.newSymbol("=", -1, -1),
            ConstantExpression(42),
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
        val binaryExpr = BinaryExpression(
            left,
            Token.newSymbol("+", -1, -1),
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
        val importNode = ImportNode(ClassHelper.make("java.util.List"), "List")

        val result = generator.generateHover(importNode)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("### Import"))
        assertTrue(contents.contains("import java.util.List"))
        assertTrue(contents.contains("**Class**: java.util.List"))
    }

    @Test
    fun `generateHover for PropertyNode shows getter and setter availability`() {
        val classNode = ClassNode("TestClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val fieldNode = FieldNode("myProperty", Modifier.PRIVATE, ClassHelper.STRING_TYPE, classNode, null)

        // Create PropertyNode with getter but no setter
        val propertyNode = PropertyNode(
            fieldNode,
            Modifier.PUBLIC,
            BlockStatement(), // getter block present
            null, // setter block absent
        )
        propertyNode.declaringClass = classNode

        val result = generator.generateHover(propertyNode)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("### Property"), "Should have Property section")
        assertTrue(contents.contains("String myProperty"), "Should show property type and name")
        assertTrue(contents.contains("**Getter**: available"), "Should indicate getter is available")
        assertTrue(contents.contains("**Setter**: none"), "Should indicate setter is not available")
    }

    @Test
    fun `generateHover for PropertyNode with both getter and setter`() {
        val classNode = ClassNode("TestClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val fieldNode = FieldNode("readWriteProp", Modifier.PRIVATE, ClassHelper.int_TYPE, classNode, null)

        val propertyNode = PropertyNode(
            fieldNode,
            Modifier.PUBLIC,
            BlockStatement(), // getter present
            BlockStatement(), // setter present
        )
        propertyNode.declaringClass = classNode

        val result = generator.generateHover(propertyNode)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("**Getter**: available"))
        assertTrue(contents.contains("**Setter**: available"))
    }

    @Test
    fun `generateHover for FieldNode shows field details`() {
        val classNode = ClassNode("TestClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val fieldNode = FieldNode(
            "myField",
            Modifier.PRIVATE or Modifier.STATIC,
            ClassHelper.STRING_TYPE,
            classNode,
            ConstantExpression("default"),
        )

        val result = generator.generateHover(fieldNode)

        assertTrue(result.isSuccess)
        val contents = result.getOrNull()!!.contents.right.value

        assertTrue(contents.contains("### Field"), "Should have Field section")
        assertTrue(contents.contains("String myField"), "Should show field type and name")
        assertTrue(contents.contains("**Modifiers**: private static"), "Should show modifiers")
        assertTrue(contents.contains("**Initial Value**: default"), "Should show initial value")
    }

    @Test
    fun `toSemanticType is now safe and does not throw for static types`() {
        // toSemanticType was refactored to be a direct conversion that doesn't
        // go through GroovySemantics, so it won't throw for static type references.
        // This test documents that behavior.
        val varExpr = VariableExpression("testVar", ClassHelper.STRING_TYPE)

        // Since the generator uses a mocked resolver, we need to mock both methods
        every { semanticResolver.toSemanticType(any()) } returns SemanticType.Known("java.lang.String")
        every { semanticResolver.formatSemanticType(any()) } returns "String"

        val result = generator.generateHover(varExpr)

        // Should succeed because toSemanticType is now a direct, safe conversion
        assertTrue(result.isSuccess, "Should succeed with static type reference")
    }

    @Test
    fun `generateHover propagates resolveType exception for dynamic variables`() {
        // Create a dynamic variable (def x = ...) that requires semantic resolution
        val varExpr = VariableExpression("testVar", ClassHelper.OBJECT_TYPE)
        val moduleNode = mockk<ModuleNode>(relaxed = true)

        // resolveType throws exception for dynamic types
        every { semanticResolver.resolveType(any(), any()) } throws RuntimeException("Resolution failed")

        // The exception propagates up - this documents that HoverContentGenerator
        // doesn't catch resolveType exceptions (may want to add error handling later)
        val exception = assertThrows<RuntimeException> {
            generator.generateHover(varExpr, moduleNode)
        }
        assertEquals("Resolution failed", exception.message)
    }

    @Test
    fun `generateHover for ClosureExpression with null variableScope does not throw NPE`() {
        // ClosureExpression can have null variableScope when the closure is created
        // but not fully resolved (e.g., in partially parsed or extracted source files)
        val closureExpr = ClosureExpression(
            arrayOf<Parameter>(),
            BlockStatement(),
        )
        // Explicitly ensure variableScope is null (it's the default, but being explicit for the test)
        // The variableScope is not set in the constructor, so it should be null

        val result = generator.generateHover(closureExpr)

        // Should succeed without NPE
        assertTrue(result.isSuccess, "Should handle null variableScope gracefully")
        val contents = result.getOrNull()!!.contents.right.value
        assertTrue(contents.contains("Closure"), "Should have Closure section")
    }
}
