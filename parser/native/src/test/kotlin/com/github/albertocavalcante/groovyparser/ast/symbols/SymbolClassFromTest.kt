package com.github.albertocavalcante.groovyparser.ast.symbols

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Symbol.Class.from() handling of NoClassDefFoundError from decompiled classpath nodes.
 *
 * When Groovy decompiles classes from JARs, accessing members (methods, fields, properties)
 * may throw NoClassDefFoundError if the class references types not available in the classloader.
 * Symbol.Class.from() must gracefully handle these errors to prevent LSP crashes.
 *
 * @see com.github.albertocavalcante.groovyparser.ast.SymbolTableBuilderTest for similar tests
 */
class SymbolClassFromTest {

    private val testUri = URI.create("file:///test/Test.groovy")

    @Test
    fun `Symbol Class from handles NoClassDefFoundError when accessing methods`() {
        val classNode = object : ClassNode("TestClass", 0, ClassHelper.OBJECT_TYPE) {
            override fun getMethods(): MutableList<MethodNode> = throw NoClassDefFoundError("org.hamcrest.Matcher")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertTrue(symbol.methods.isEmpty(), "Methods should be empty when access throws")
    }

    @Test
    fun `Symbol Class from handles NoClassDefFoundError when accessing fields`() {
        val classNode = object : ClassNode("TestClass", 0, ClassHelper.OBJECT_TYPE) {
            override fun getFields(): MutableList<FieldNode> = throw NoClassDefFoundError("org.hamcrest.Matcher")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertTrue(symbol.fields.isEmpty(), "Fields should be empty when access throws")
    }

    @Test
    fun `Symbol Class from handles NoClassDefFoundError when accessing properties`() {
        val classNode = object : ClassNode("TestClass", 0, ClassHelper.OBJECT_TYPE) {
            override fun getProperties(): MutableList<PropertyNode> = throw NoClassDefFoundError("org.hamcrest.Matcher")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertTrue(symbol.properties.isEmpty(), "Properties should be empty when access throws")
    }

    @Test
    fun `Symbol Class from handles NoClassDefFoundError when accessing superClass`() {
        val classNode = object : ClassNode("TestClass", 0, null) {
            override fun getSuperClass(): ClassNode = throw NoClassDefFoundError("com.example.MissingSuperClass")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertNull(symbol.superClass, "SuperClass should be null when access throws")
    }

    @Test
    fun `Symbol Class from handles NoClassDefFoundError when accessing interfaces`() {
        val classNode = object : ClassNode("TestClass", 0, ClassHelper.OBJECT_TYPE) {
            override fun getInterfaces(): Array<ClassNode> = throw NoClassDefFoundError("com.example.MissingInterface")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertTrue(symbol.interfaces.isEmpty(), "Interfaces should be empty when access throws")
    }

    @Test
    fun `Symbol Class from handles all NoClassDefFoundError gracefully`() {
        // Create a "fully exploding" ClassNode that throws on all member accesses
        val classNode = object : ClassNode("ExplodingClass", 0, null) {
            override fun getMethods(): MutableList<MethodNode> = throw NoClassDefFoundError("hudson.model.TaskListener")

            override fun getFields(): MutableList<FieldNode> = throw NoClassDefFoundError("hudson.model.TaskListener")

            override fun getProperties(): MutableList<PropertyNode> =
                throw NoClassDefFoundError("hudson.model.TaskListener")

            override fun getSuperClass(): ClassNode = throw NoClassDefFoundError("hudson.model.TaskListener")

            override fun getInterfaces(): Array<ClassNode> = throw NoClassDefFoundError("hudson.model.TaskListener")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("ExplodingClass", symbol.name)
        assertTrue(symbol.methods.isEmpty(), "Methods should be empty")
        assertTrue(symbol.fields.isEmpty(), "Fields should be empty")
        assertTrue(symbol.properties.isEmpty(), "Properties should be empty")
        assertNull(symbol.superClass, "SuperClass should be null")
        assertTrue(symbol.interfaces.isEmpty(), "Interfaces should be empty")
    }

    @Test
    fun `Symbol Class from works correctly for normal ClassNode`() {
        val classNode = ClassNode("NormalClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)

        // Add a test method
        val methodNode = MethodNode(
            "testMethod",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            emptyArray(),
            emptyArray(),
            null,
        )
        classNode.addMethod(methodNode)

        // Add a test field
        val fieldNode = FieldNode(
            "testField",
            Modifier.PRIVATE,
            ClassHelper.STRING_TYPE,
            classNode,
            null,
        )
        classNode.addField(fieldNode)

        val symbol = Symbol.Class.from(classNode, testUri)

        assertEquals("NormalClass", symbol.name)
        assertEquals(1, symbol.methods.size, "Should have one method")
        assertEquals("testMethod", symbol.methods.first().name)
        assertEquals(1, symbol.fields.size, "Should have one field")
        assertEquals("testField", symbol.fields.first().name)
        assertNotNull(symbol.superClass, "Should have OBJECT_TYPE as superClass")
    }

    @Test
    fun `Symbol Class from handles LinkageError when accessing methods`() {
        // LinkageError is the parent class of NoClassDefFoundError
        val classNode = object : ClassNode("TestClass", 0, ClassHelper.OBJECT_TYPE) {
            override fun getMethods(): MutableList<MethodNode> = throw LinkageError("Generic linkage error")
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertTrue(symbol.methods.isEmpty(), "Methods should be empty when access throws LinkageError")
    }

    @Test
    fun `Symbol Class from preserves basic class info even when members throw`() {
        val classNode = object : ClassNode("com.example.TestClass", Modifier.PUBLIC, null) {
            init {
                setInterfaces(arrayOf(ClassHelper.make(java.io.Serializable::class.java)))
            }

            override fun getMethods(): MutableList<MethodNode> = throw NoClassDefFoundError("missing.Dependency")

            override fun getFields(): MutableList<FieldNode> = throw NoClassDefFoundError("missing.Dependency")

            override fun getProperties(): MutableList<PropertyNode> = throw NoClassDefFoundError("missing.Dependency")

            // Override getInterfaces to not throw - test partial failure
            override fun getInterfaces(): Array<ClassNode> = arrayOf(ClassHelper.make(java.io.Serializable::class.java))
        }

        val symbol = assertDoesNotThrow { Symbol.Class.from(classNode, testUri) }

        assertEquals("TestClass", symbol.name)
        assertEquals("com.example", symbol.packageName)
        assertEquals(Visibility.PUBLIC, symbol.visibility)
        // Interfaces should still work since they don't throw
        assertEquals(1, symbol.interfaces.size, "Should preserve working interface access")
        // Members should be empty
        assertTrue(symbol.methods.isEmpty())
        assertTrue(symbol.fields.isEmpty())
        assertTrue(symbol.properties.isEmpty())
    }
}
