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

        assertEquals("scip-groovy maven com.example 0.0.0 com.example.MyClass#myMethod(java.lang.String).", symbol)
    }

    @Test
    fun `should handle missing package name`() {
        val generator = SymbolGenerator()
        val classNode = ClassNode("MyClass", 0, null) // No package

        val symbol = generator.forClass(classNode)

        assertEquals("scip-groovy maven . 0.0.0 MyClass#", symbol)
    }
}
