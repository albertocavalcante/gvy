package com.github.albertocavalcante.gvy.semantics.delegation

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Tests for DelegationResolver - resolving implicit receivers in closures.
 * Based on IntelliJ's delegatesTo package pattern.
 *
 * TODO(#638): These tests require real AST context from parsed Groovy code.
 * They are disabled until the integration with the parser is complete.
 */
class DelegationResolverTest {

    @Test
    fun `DelegationStrategy has correct values`() {
        assertEquals(0, DelegationStrategy.OWNER_FIRST.value)
        assertEquals(1, DelegationStrategy.DELEGATE_FIRST.value)
        assertEquals(2, DelegationStrategy.OWNER_ONLY.value)
        assertEquals(3, DelegationStrategy.DELEGATE_ONLY.value)
        assertEquals(4, DelegationStrategy.TO_SELF.value)
    }

    @Test
    fun `DelegationStrategy fromValue returns correct strategy`() {
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.fromValue(0))
        assertEquals(DelegationStrategy.DELEGATE_FIRST, DelegationStrategy.fromValue(1))
        assertEquals(DelegationStrategy.OWNER_ONLY, DelegationStrategy.fromValue(2))
        assertEquals(DelegationStrategy.DELEGATE_ONLY, DelegationStrategy.fromValue(3))
        assertEquals(DelegationStrategy.TO_SELF, DelegationStrategy.fromValue(4))
    }

    @Test
    fun `DelegationStrategy fromValue returns default for unknown value`() {
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.fromValue(99))
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.fromValue(-1))
    }

    @Test
    fun `DelegationStrategy DEFAULT is OWNER_FIRST`() {
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.DEFAULT)
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves this in closure - closure's own methods`() {
        // Given: { method() } where method is defined on the closure itself
        // When: resolving the method call without explicit receiver
        // Then: should find method on 'this' (the closure)
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves owner when this fails`() {
        // Given: class Foo { def bar() { { method() } } }
        // When: method() is not on closure, check owner (Foo)
        // Then: should resolve to Foo.method()
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves delegate when set`() {
        // Given: closure.delegate = builder; closure.call()
        // When: resolving name() inside closure
        // Then: should check builder.name()
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `respects DelegatesTo annotation`() {
        // Given: void run(@DelegatesTo(Builder) Closure c)
        // When: c is called with { name = 'foo' }
        // Then: name should resolve to Builder.name
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `respects OWNER_FIRST strategy`() {
        // Given: closure with delegate set, strategy = OWNER_FIRST
        // When: both owner and delegate have method 'foo'
        // Then: should resolve to owner.foo
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `respects DELEGATE_FIRST strategy`() {
        // Given: closure with delegate set, strategy = DELEGATE_FIRST
        // Note: DELEGATE_FIRST is NOT the default. OWNER_FIRST is the default per Groovy spec:
        // https://docs.groovy-lang.org/latest/html/documentation/core-closures.html#_delegation_strategy
        // When: both owner and delegate have method 'foo'
        // Then: should resolve to delegate.foo
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves Gradle DSL dependencies block`() {
        // Given: dependencies { implementation 'foo' }
        // When: resolving 'implementation' method
        // Then: should resolve to DependencyHandler.implementation
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves Jenkins pipeline sh step`() {
        // Given: pipeline { stage('Build') { sh 'make' } }
        // When: resolving 'sh' method
        // Then: should resolve to pipeline step
    }

    // ==================== Method Overload Resolution Tests ====================

    @Test
    fun `findMethod resolves overloaded method with no parameters`() {
        val classNode = createClassWithOverloadedMethods()
        val resolver = DelegationResolver()

        val method = resolver.findMethodWithParams(classNode, "process", emptyList())

        assertNotNull(method, "Should find method with no parameters")
        assertEquals(0, method!!.parameters.size)
    }

    @Test
    fun `findMethod resolves overloaded method with one String parameter`() {
        val classNode = createClassWithOverloadedMethods()
        val resolver = DelegationResolver()

        val method = resolver.findMethodWithParams(classNode, "process", listOf("java.lang.String"))

        assertNotNull(method, "Should find method with String parameter")
        assertEquals(1, method!!.parameters.size)
        assertEquals("java.lang.String", method.parameters[0].type.name)
    }

    @Test
    fun `findMethod resolves overloaded method with two parameters`() {
        val classNode = createClassWithOverloadedMethods()
        val resolver = DelegationResolver()

        val method = resolver.findMethodWithParams(classNode, "process", listOf("java.lang.String", "int"))

        assertNotNull(method, "Should find method with String and int parameters")
        assertEquals(2, method!!.parameters.size)
        assertEquals("java.lang.String", method.parameters[0].type.name)
        assertEquals("int", method.parameters[1].type.name)
    }

    @Test
    fun `findMethod returns null when parameter count does not match any overload`() {
        val classNode = createClassWithOverloadedMethods()
        val resolver = DelegationResolver()

        val method = resolver.findMethodWithParams(
            classNode,
            "process",
            listOf("java.lang.String", "int", "boolean"),
        )

        assertNull(method, "Should return null when no matching overload exists")
    }

    @Test
    fun `findMethod distinguishes between different parameter types`() {
        val classNode = createClassWithOverloadedMethods()
        val resolver = DelegationResolver()

        val intMethod = resolver.findMethodWithParams(classNode, "process", listOf("int"))
        val stringMethod = resolver.findMethodWithParams(classNode, "process", listOf("java.lang.String"))

        assertNotNull(intMethod, "Should find method with int parameter")
        assertNotNull(stringMethod, "Should find method with String parameter")
        assertEquals("int", intMethod!!.parameters[0].type.name)
        assertEquals("java.lang.String", stringMethod!!.parameters[0].type.name)
    }

    @Test
    fun `findMethod without parameter types falls back to first match`() {
        val classNode = createClassWithOverloadedMethods()
        val resolver = DelegationResolver()

        val method = resolver.findMethodWithParams(classNode, "process", null)

        assertNotNull(method, "Should return first matching method when no params specified")
    }

    // Helper methods for tests

    /**
     * Creates a test class with overloaded methods for testing method resolution.
     */
    private fun createClassWithOverloadedMethods(): ClassNode {
        val classNode = ClassNode("TestClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)

        // process() - no parameters
        classNode.addMethod(
            MethodNode(
                "process",
                Modifier.PUBLIC,
                ClassHelper.VOID_TYPE,
                emptyArray(),
                emptyArray(),
                null,
            ),
        )

        // process(String) - one String parameter
        classNode.addMethod(
            MethodNode(
                "process",
                Modifier.PUBLIC,
                ClassHelper.VOID_TYPE,
                arrayOf(Parameter(ClassHelper.STRING_TYPE, "name")),
                emptyArray(),
                null,
            ),
        )

        // process(int) - one int parameter
        classNode.addMethod(
            MethodNode(
                "process",
                Modifier.PUBLIC,
                ClassHelper.VOID_TYPE,
                arrayOf(Parameter(ClassHelper.int_TYPE, "count")),
                emptyArray(),
                null,
            ),
        )

        // process(String, int) - two parameters
        classNode.addMethod(
            MethodNode(
                "process",
                Modifier.PUBLIC,
                ClassHelper.VOID_TYPE,
                arrayOf(
                    Parameter(ClassHelper.STRING_TYPE, "name"),
                    Parameter(ClassHelper.int_TYPE, "count"),
                ),
                emptyArray(),
                null,
            ),
        )

        return classNode
    }
}
