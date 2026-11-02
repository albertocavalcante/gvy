package com.github.albertocavalcante.groovylsp.indexing

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolGeneratorTest {

    @Test
    fun `should generate symbol with default scheme and manager`() {
        val generator = SymbolGenerator()
        val classNode = ClassNode("com.example.MyClass", 0, null)

        val symbol = generator.forClass(classNode)

        assertEquals("scip-groovy maven com.example 0.0.0 com.example.MyClass#", symbol)
    }

    @Test
    fun `should generate symbol with custom scheme and manager`() {
        val generator = SymbolGenerator(scheme = "scip-java", manager = "gradle")
        val classNode = ClassNode("com.example.MyClass", 0, null)

        val symbol = generator.forClass(classNode)

        assertEquals("scip-java gradle com.example 0.0.0 com.example.MyClass#", symbol)
    }

    @Test
    fun `should generate method symbol`() {
        val generator = SymbolGenerator()
        val classNode = ClassNode("com.example.MyClass", 0, null)
        val methodNode = MethodNode(
            "myMethod",
            0,
            null,
            arrayOf(Parameter(ClassNode("java.lang.String", 0, null), "p1")),
            null,
            null,
        )

        val symbol = generator.forMethod(classNode, methodNode)

        assertEquals(
            "scip-groovy maven com.example 0.0.0 com.example.MyClass#myMethod(amF2YS5sYW5nLlN0cmluZw).",
            symbol,
        )
    }

    @Test
    fun `method symbol encodes multiple qualified parameter types`() {
        val generator = SymbolGenerator()
        val classNode = ClassNode("com.example.MyClass", 0, null)
        val methodNode = MethodNode(
            "combine",
            0,
            null,
            arrayOf(
                Parameter(ClassNode("java.lang.String", 0, null), "left"),
                Parameter(ClassNode("java.lang.Integer", 0, null), "right"),
            ),
            null,
            null,
        )

        assertEquals(
            "scip-groovy maven com.example 0.0.0 com.example.MyClass#combine(" +
                "amF2YS5sYW5nLlN0cmluZwBqYXZhLmxhbmcuSW50ZWdlcg).",
            generator.forMethod(classNode, methodNode),
        )
    }

    @Test
    fun `overloads have distinct simple disambiguators`() {
        val generator = SymbolGenerator()
        val classNode = ClassNode("com.example.MyClass", 0, null)
        fun method(vararg types: String) = MethodNode(
            "combine",
            0,
            null,
            types.mapIndexed { index, type -> Parameter(ClassNode(type, 0, null), "p$index") }.toTypedArray(),
            null,
            null,
        )

        val noArgs = generator.forMethod(classNode, method())
        val stringArg = generator.forMethod(classNode, method("java.lang.String"))
        val integerArg = generator.forMethod(classNode, method("java.lang.Integer"))
        val twoArgs = generator.forMethod(classNode, method("java.lang.String", "java.lang.Integer"))

        assertEquals("scip-groovy maven com.example 0.0.0 com.example.MyClass#combine().", noArgs)
        assertEquals(4, setOf(noArgs, stringArg, integerArg, twoArgs).size)
    }

    @Test
    fun `should handle missing package name`() {
        val generator = SymbolGenerator()
        val classNode = ClassNode("MyClass", 0, null) // No package

        val symbol = generator.forClass(classNode)

        assertEquals("scip-groovy maven . 0.0.0 MyClass#", symbol)
    }

    @Test
    fun `local generates local symbol`() {
        val generator = SymbolGenerator()

        assertEquals("local 123", generator.local(123))
    }
}
