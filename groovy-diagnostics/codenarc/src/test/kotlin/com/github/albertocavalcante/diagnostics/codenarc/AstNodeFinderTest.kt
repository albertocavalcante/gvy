package com.github.albertocavalcante.diagnostics.codenarc

import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for AstNodeFinder - locates AST nodes by line number for precise diagnostic positioning.
 *
 * These tests verify that we can find the correct AST nodes based on violation line numbers,
 * enabling AST-based diagnostic positioning instead of heuristic string matching.
 */
class AstNodeFinderTest {

    // ==========================================
    // findClassAtLine TESTS
    // ==========================================

    @Test
    fun `findClassAtLine returns ClassNode when class declared at line`() {
        val code = """
            package com.example

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // "class MyClass {" is on line 3 (1-based)
        val classNode = finder.findClassAtLine(3)

        assertNotNull(classNode, "Should find ClassNode at line 3")
        assertEquals("MyClass", classNode.nameWithoutPackage)
    }

    @Test
    fun `findClassAtLine returns null when no class at line`() {
        val code = """
            package com.example

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Line 1 is package declaration, not a class
        val classNode = finder.findClassAtLine(1)

        assertNull(classNode, "Should not find ClassNode at line 1 (package declaration)")
    }

    @Test
    fun `findClassAtLine finds class with modifiers`() {
        val code = """
            public abstract class AbstractService {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        val classNode = finder.findClassAtLine(1)

        assertNotNull(classNode, "Should find ClassNode with modifiers")
        assertEquals("AbstractService", classNode.nameWithoutPackage)
    }

    @Test
    fun `findClassAtLine finds nested class`() {
        val code = """
            class Outer {
                class Inner {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Inner class is on line 2
        val innerClass = finder.findClassAtLine(2)

        assertNotNull(innerClass, "Should find nested ClassNode")
        // Inner class name includes outer class prefix in Groovy AST
        assertEquals("Outer\$Inner", innerClass.nameWithoutPackage)
    }

    // ==========================================
    // findMethodAtLine TESTS
    // ==========================================

    @Test
    fun `findMethodAtLine returns MethodNode when method declared at line`() {
        val code = """
            class MyClass {
                void myMethod() {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // "void myMethod() {" is on line 2 (1-based)
        val methodNode = finder.findMethodAtLine(2)

        assertNotNull(methodNode, "Should find MethodNode at line 2")
        assertEquals("myMethod", methodNode.name)
    }

    @Test
    fun `findMethodAtLine returns null when no method at line`() {
        val code = """
            class MyClass {
                void myMethod() {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Line 1 is class declaration, not a method
        val methodNode = finder.findMethodAtLine(1)

        assertNull(methodNode, "Should not find MethodNode at line 1 (class declaration)")
    }

    @Test
    fun `findMethodAtLine finds method with modifiers`() {
        val code = """
            class MyClass {
                public static void staticMethod() {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        val methodNode = finder.findMethodAtLine(2)

        assertNotNull(methodNode, "Should find MethodNode with modifiers")
        assertEquals("staticMethod", methodNode.name)
    }

    @Test
    fun `findMethodAtLine finds method in nested class`() {
        val code = """
            class Outer {
                class Inner {
                    void innerMethod() {
                    }
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // innerMethod is on line 3
        val methodNode = finder.findMethodAtLine(3)

        assertNotNull(methodNode, "Should find MethodNode in nested class")
        assertEquals("innerMethod", methodNode.name)
    }

    // ==========================================
    // findFieldAtLine TESTS
    // ==========================================

    @Test
    fun `findFieldAtLine returns FieldNode when field declared at line`() {
        val code = """
            class MyClass {
                String myField
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // "String myField" is on line 2 (1-based)
        val fieldNode = finder.findFieldAtLine(2)

        assertNotNull(fieldNode, "Should find FieldNode at line 2")
        assertEquals("myField", fieldNode.name)
    }

    @Test
    fun `findFieldAtLine returns null when no field at line`() {
        val code = """
            class MyClass {
                String myField
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Line 1 is class declaration, not a field
        val fieldNode = finder.findFieldAtLine(1)

        assertNull(fieldNode, "Should not find FieldNode at line 1 (class declaration)")
    }

    @Test
    fun `findFieldAtLine finds field with initializer`() {
        val code = """
            class MyClass {
                int count = 0
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        val fieldNode = finder.findFieldAtLine(2)

        assertNotNull(fieldNode, "Should find FieldNode with initializer")
        assertEquals("count", fieldNode.name)
    }

    @Test
    fun `findFieldAtLine finds field in nested class`() {
        val code = """
            class Outer {
                class Inner {
                    String innerField
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // innerField is on line 3
        val fieldNode = finder.findFieldAtLine(3)

        assertNotNull(fieldNode, "Should find FieldNode in nested class")
        assertEquals("innerField", fieldNode.name)
    }

    // ==========================================
    // findVariableAtLine TESTS
    // ==========================================

    @Test
    fun `findVariableAtLine returns Variable when local variable declared at line`() {
        val code = """
            class MyClass {
                void myMethod() {
                    String localVar = "test"
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // "String localVar = ..." is on line 3 (1-based)
        val variable = finder.findVariableAtLine(3, "localVar")

        assertNotNull(variable, "Should find variable at line 3")
        assertEquals("localVar", variable.name)
    }

    @Test
    fun `findVariableAtLine returns null when variable name does not match`() {
        val code = """
            class MyClass {
                void myMethod() {
                    String localVar = "test"
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Wrong variable name
        val variable = finder.findVariableAtLine(3, "wrongName")

        assertNull(variable, "Should not find variable with wrong name")
    }

    @Test
    fun `findVariableAtLine returns null when no variable at line`() {
        val code = """
            class MyClass {
                void myMethod() {
                    String localVar = "test"
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Line 2 is method declaration, not a variable
        val variable = finder.findVariableAtLine(2, "localVar")

        assertNull(variable, "Should not find variable at line 2 (method declaration)")
    }

    // ==========================================
    // findImportAtLine TESTS
    // ==========================================

    @Test
    fun `findImportAtLine returns ImportNode when import declared at line`() {
        val code = """
            import java.util.List

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // "import java.util.List" is on line 1 (1-based)
        val importNode = finder.findImportAtLine(1)

        assertNotNull(importNode, "Should find ImportNode at line 1")
        assertEquals("java.util.List", importNode.className)
    }

    @Test
    fun `findImportAtLine returns null when no import at line`() {
        val code = """
            import java.util.List

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        // Line 3 is class declaration, not an import
        val importNode = finder.findImportAtLine(3)

        assertNull(importNode, "Should not find ImportNode at line 3 (class declaration)")
    }

    @Test
    fun `findImportAtLine finds star import`() {
        val code = """
            import java.util.*

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        val importNode = finder.findImportAtLine(1)

        assertNotNull(importNode, "Should find star ImportNode")
        // Star import has packageName with trailing dot
        assertEquals("java.util.", importNode.packageName)
    }

    @Test
    fun `findImportAtLine finds static import`() {
        val code = """
            import static java.lang.Math.PI

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val finder = AstNodeFinder(module)

        val importNode = finder.findImportAtLine(1)

        assertNotNull(importNode, "Should find static ImportNode")
    }

    // ==========================================
    // HELPER FUNCTIONS
    // ==========================================

    /**
     * Parses Groovy code and returns the ModuleNode (native Groovy AST).
     *
     * This replicates the same parsing done by GroovyParserFacade, producing
     * the same AST that would be cached by the LSP compilation service.
     */
    private fun parseToModuleNode(code: String): ModuleNode {
        val config = CompilerConfiguration()
        val compilationUnit = CompilationUnit(config)
        compilationUnit.addSource("TestScript.groovy", code)
        compilationUnit.compile(Phases.CONVERSION)

        val compileUnit = compilationUnit.ast
        val moduleNode = compileUnit?.modules?.firstOrNull()
        return requireNotNull(moduleNode) { "Failed to parse code to AST" }
    }
}
