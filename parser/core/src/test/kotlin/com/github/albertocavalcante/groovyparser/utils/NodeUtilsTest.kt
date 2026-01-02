package com.github.albertocavalcante.groovyparser.utils

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeUtilsTest {

    private val testCode = """
        class Foo {
            String name
            int count
            
            def greet() {
                println "Hello"
                println name
            }
            
            def calculate(x, y) {
                return x + y
            }
        }
        
        class Bar {
            def test() {
                println "Bar"
            }
        }
    """.trimIndent()

    private fun parse() = GroovyParser().parse(testCode).also {
        assertTrue(it.isSuccessful)
    }.result.get()

    @Test
    fun `findAll finds all nodes of a type`() {
        val cu = parse()

        val classes = NodeUtils.findAll<ClassDeclaration>(cu)
        assertEquals(2, classes.size)

        val methods = NodeUtils.findAll<MethodDeclaration>(cu)
        assertTrue(methods.size >= 3) // greet, calculate, test

        val methodCalls = NodeUtils.findAll<MethodCallExpr>(cu)
        assertTrue(methodCalls.isNotEmpty())
    }

    @Test
    fun `findFirst finds first node of a type`() {
        val cu = parse()

        val firstClass = NodeUtils.findFirst<ClassDeclaration>(cu)
        assertNotNull(firstClass)
        assertEquals("Foo", firstClass.name)
    }

    @Test
    fun `findClass finds class by name`() {
        val cu = parse()

        val foo = NodeUtils.findClass(cu, "Foo")
        assertNotNull(foo)
        assertEquals("Foo", foo.name)

        val bar = NodeUtils.findClass(cu, "Bar")
        assertNotNull(bar)
        assertEquals("Bar", bar.name)

        val notFound = NodeUtils.findClass(cu, "NotFound")
        assertNull(notFound)
    }

    @Test
    fun `findMethod finds method by name`() {
        val cu = parse()
        val foo = NodeUtils.findClass(cu, "Foo")!!

        val greet = NodeUtils.findMethod(foo, "greet")
        assertNotNull(greet)
        assertEquals("greet", greet.name)

        val calculate = NodeUtils.findMethod(foo, "calculate")
        assertNotNull(calculate)

        val notFound = NodeUtils.findMethod(foo, "notFound")
        assertNull(notFound)
    }

    @Test
    fun `findField finds field by name`() {
        val cu = parse()
        val foo = NodeUtils.findClass(cu, "Foo")!!

        val name = NodeUtils.findField(foo, "name")
        assertNotNull(name)
        assertEquals("name", name.name)

        val notFound = NodeUtils.findField(foo, "notFound")
        assertNull(notFound)
    }

    @Test
    fun `findMethodCalls finds calls to specific method`() {
        val cu = parse()

        val printlnCalls = NodeUtils.findMethodCalls(cu, "println")
        assertTrue(printlnCalls.size >= 3)
    }

    @Test
    fun `getAncestors returns ancestors up to root`() {
        val cu = parse()
        val foo = NodeUtils.findClass(cu, "Foo")!!
        val method = NodeUtils.findMethod(foo, "greet")!!

        val ancestors = NodeUtils.getAncestors(method)
        assertTrue(ancestors.contains(foo))
        assertTrue(ancestors.any { it is CompilationUnit })
    }

    @Test
    fun `findAncestor finds nearest ancestor of type`() {
        val cu = parse()
        val foo = NodeUtils.findClass(cu, "Foo")!!
        val method = NodeUtils.findMethod(foo, "greet")!!

        val containingClass = NodeUtils.findAncestor<ClassDeclaration>(method)
        assertNotNull(containingClass)
        assertEquals("Foo", containingClass.name)
    }

    @Test
    fun `getContainingClass returns containing class`() {
        val cu = parse()
        val foo = NodeUtils.findClass(cu, "Foo")!!
        val method = NodeUtils.findMethod(foo, "greet")!!

        val containing = NodeUtils.getContainingClass(method)
        assertNotNull(containing)
        assertEquals("Foo", containing.name)
    }

    @Test
    fun `countNodes counts all nodes in AST`() {
        val cu = parse()
        val count = NodeUtils.countNodes(cu)
        assertTrue(count > 10) // Should have many nodes
    }

    @Test
    fun `getDepth returns AST depth`() {
        val cu = parse()
        val depth = NodeUtils.getDepth(cu)
        assertTrue(depth > 1)
    }

    @Test
    fun `walkPreOrder visits all nodes`() {
        val cu = parse()
        val visited = mutableListOf<String>()

        NodeUtils.walkPreOrder(cu) { node ->
            when (node) {
                is ClassDeclaration -> visited.add("class:${node.name}")
                is MethodDeclaration -> visited.add("method:${node.name}")
                else -> {}
            }
        }

        assertTrue(visited.contains("class:Foo"))
        assertTrue(visited.contains("class:Bar"))
        assertTrue(visited.any { it.startsWith("method:") })
    }

    @Test
    fun `extension functions work correctly`() {
        val cu = parse()

        val classes = cu.findAll<ClassDeclaration>()
        assertEquals(2, classes.size)

        val firstMethod = cu.findFirst<MethodDeclaration>()
        assertNotNull(firstMethod)

        val foo = NodeUtils.findClass(cu, "Foo")!!
        val method = NodeUtils.findMethod(foo, "greet")!!

        val containingClass = method.findAncestor<ClassDeclaration>()
        assertNotNull(containingClass)
        assertEquals("Foo", containingClass.name)
    }

    @Test
    fun `getLeafNodes returns nodes without children`() {
        val cu = parse()
        val leaves = NodeUtils.getLeafNodes(cu)
        assertTrue(leaves.isNotEmpty())
        // Leaf nodes should have no children
        leaves.forEach { assertTrue(it.getChildNodes().isEmpty()) }
    }
}
