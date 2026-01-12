package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * QA-focused tests for DeclarationWalker.
 *
 * These tests are designed to find bugs, not confirm the implementation works.
 * Each test targets a specific edge case or potential failure mode.
 */
class DeclarationWalkerTest {

    private val parser = GroovyParserFacade()

    private val stubSolver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> = SymbolReference.unsolved()
    }

    private fun parse(code: String): ModuleNode {
        val request = ParseRequest(URI.create("file:///Test.groovy"), code)
        val result = parser.parse(request)
        if (!result.isSuccessful) {
            error("Parse failed: ${result.diagnostics}")
        }
        return result.ast!!
    }

    private fun getContext(module: ModuleNode): NativeTypeContext {
        val semantics = GroovySemantics(stubSolver)
        return semantics.getContext(module)
            ?: error("Failed to get context for module")
    }

    private fun getMethodBlock(module: ModuleNode, methodName: String): BlockStatement {
        val classNode = module.classes.first()
        val method = classNode.methods.find { it.name == methodName }
            ?: error("Method '$methodName' not found")
        return method.code as? BlockStatement
            ?: error("Method '$methodName' has no BlockStatement body")
    }

    private fun getScriptBlock(module: ModuleNode): BlockStatement = module.statementBlock
        ?: error("Module has no statement block (not a script?)")

    // ==========================================
    // Basic Functionality Tests
    // ==========================================

    @Nested
    inner class BasicFunctionality {

        @Test
        fun `empty block returns no declarations`() {
            val code = """
                class Foo {
                    def empty() {
                        // nothing here
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "empty")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(0, result.variables.size, "Empty block should have no declarations")
        }

        @Test
        fun `single declaration is captured`() {
            val code = """
                class Foo {
                    def test() {
                        def x = 42
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(1, result.variables.size)
            assertEquals("x", result.variables[0].name)
            assertEquals(TypeConstants.INT, result.variables[0].inferredType)
        }

        @Test
        fun `multiple declarations are captured in order`() {
            val code = """
                class Foo {
                    def test() {
                        def a = "first"
                        def b = 2
                        def c = 3.14
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(3, result.variables.size)
            assertEquals("a", result.variables[0].name)
            assertEquals("b", result.variables[1].name)
            assertEquals("c", result.variables[2].name)
        }
    }

    // ==========================================
    // Nested Block Tests - Potential Bug Area!
    // ==========================================

    @Nested
    inner class NestedBlocks {

        @Test
        fun `declaration in simple nested block is captured`() {
            val code = """
                class Foo {
                    def test() {
                        {
                            def nested = 1
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(1, result.variables.size, "Declaration in nested block should be captured")
            assertEquals("nested", result.variables[0].name)
        }

        @Test
        fun `declaration inside if block should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        if (true) {
                            def inIf = 1
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            // Regression test: ensure declarations inside IfStatement blocks are traversed and captured
            assertEquals(1, result.variables.size, "Declaration inside if block should be captured")
            assertEquals("inIf", result.variables[0].name)
        }

        @Test
        fun `declaration inside for loop should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        for (int i = 0; i < 10; i++) {
                            def loopVar = i * 2
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            // Both loop variable 'i' and body variable 'loopVar' should be captured
            assertTrue(
                result.variables.any { it.name == "i" },
                "For loop variable 'i' should be captured",
            )
            // Ensure no duplicates
            assertEquals(
                1,
                result.variables.count { it.name == "i" },
                "For loop variable 'i' should be captured exactly once",
            )
            assertTrue(
                result.variables.any { it.name == "loopVar" },
                "Declaration inside for loop body should be captured",
            )
        }

        @Test
        fun `declaration inside while loop should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        while (true) {
                            def whileVar = 1
                            break
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(1, result.variables.size, "Declaration inside while loop should be captured")
        }

        @Test
        fun `declaration inside try-catch should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        try {
                            def inTry = 1
                        } catch (Exception e) {
                            def inCatch = 2
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertTrue(
                result.variables.any { it.name == "inTry" },
                "Declaration in try block should be captured",
            )
            assertTrue(
                result.variables.any { it.name == "inCatch" },
                "Declaration in catch block should be captured",
            )
        }

        @Test
        fun `catch exception variable should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        try {
                            throw new RuntimeException("test")
                        } catch (Exception myException) {
                            println myException.message
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertTrue(
                result.variables.any { it.name == "myException" },
                "Catch exception variable 'myException' should be captured. Found: ${result.variables.map { it.name }}",
            )
        }

        @Test
        fun `declaration inside do-while loop should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        do {
                            def doWhileVar = 1
                        } while (false)
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(1, result.variables.size, "Declaration inside do-while should be captured")
            assertEquals("doWhileVar", result.variables[0].name)
        }

        @Test
        fun `declaration inside switch case should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        switch (1) {
                            case 1:
                                def caseVar = "one"
                                break
                            case 2:
                                def case2Var = "two"
                                break
                            default:
                                def defaultVar = "default"
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertTrue(
                result.variables.any { it.name == "caseVar" },
                "Declaration inside switch case should be captured. Found: ${result.variables.map { it.name }}",
            )
            assertTrue(
                result.variables.any { it.name == "case2Var" },
                "Declaration inside second switch case should be captured",
            )
            assertTrue(
                result.variables.any { it.name == "defaultVar" },
                "Declaration inside switch default should be captured",
            )
        }

        @Test
        fun `for-each loop variable should be captured`() {
            val code = """
                class Foo {
                    def test() {
                        def items = [1, 2, 3]
                        for (item in items) {
                            def doubled = item * 2
                        }
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertTrue(
                result.variables.any { it.name == "item" },
                "For-each loop variable 'item' should be captured. Found: ${result.variables.map { it.name }}",
            )
            assertTrue(
                result.variables.any { it.name == "doubled" },
                "Declaration inside for-each loop body should be captured",
            )
        }
    }

    // ==========================================
    // Map Key Extraction Tests
    // ==========================================

    @Nested
    inner class MapKeyExtraction {

        @Test
        fun `captureMapKeys false returns null for mapKeys`() {
            val code = """
                class Foo {
                    def test() {
                        def m = [a: 1, b: 2]
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = false)

            assertEquals(1, result.variables.size)
            assertNull(
                result.variables[0].mapKeys,
                "When captureMapKeys=false, mapKeys should be null even for map literal",
            )
        }

        @Test
        fun `captureMapKeys true extracts string keys`() {
            val code = """
                class Foo {
                    def test() {
                        def m = ["foo": 1, "bar": 2]
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = true)

            assertEquals(1, result.variables.size)
            assertNotNull(result.variables[0].mapKeys, "mapKeys should not be null for map literal")

            val keys = result.variables[0].mapKeys!!
            assertEquals(2, keys.size)
            assertTrue(keys.any { it.key == "foo" }, "Should extract key 'foo'")
            assertTrue(keys.any { it.key == "bar" }, "Should extract key 'bar'")
        }

        @Test
        fun `bareword map keys are extracted`() {
            val code = """
                class Foo {
                    def test() {
                        def m = [bareword1: 1, bareword2: "two"]
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = true)

            val keys = result.variables[0].mapKeys!!
            assertEquals(2, keys.size)
            assertTrue(keys.any { it.key == "bareword1" }, "Should extract bareword key")
            assertTrue(keys.any { it.key == "bareword2" }, "Should extract bareword key")
        }

        @Test
        fun `empty map returns empty mapKeys list, not null`() {
            val code = """
                class Foo {
                    def test() {
                        def m = [:]
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = true)

            assertNotNull(
                result.variables[0].mapKeys,
                "Empty map should still have mapKeys (empty list), not null",
            )
            assertEquals(
                0,
                result.variables[0].mapKeys!!.size,
                "Empty map should have empty mapKeys list",
            )
        }

        @Test
        fun `non-map declaration has null mapKeys`() {
            val code = """
                class Foo {
                    def test() {
                        def x = 42
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = true)

            assertNull(
                result.variables[0].mapKeys,
                "Non-map declaration should have null mapKeys, not empty list",
            )
        }

        @Test
        fun `map key value types are correctly inferred`() {
            val code = """
                class Foo {
                    def test() {
                        def m = [
                            strVal: "hello",
                            intVal: 42,
                            listVal: [1, 2, 3]
                        ]
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = true)

            val keys = result.variables[0].mapKeys!!
            val strEntry = keys.find { it.key == "strVal" }
            val intEntry = keys.find { it.key == "intVal" }
            val listEntry = keys.find { it.key == "listVal" }

            assertNotNull(strEntry)
            assertNotNull(intEntry)
            assertNotNull(listEntry)

            assertEquals(TypeConstants.STRING, strEntry!!.valueType)
            assertEquals(TypeConstants.INT, intEntry!!.valueType)
            assertTrue(
                listEntry!!.valueType is SemanticType.Known &&
                    listEntry.valueType.fqn.contains("ArrayList"),
                "List value should resolve to ArrayList, got: ${listEntry.valueType}",
            )
        }

        @Test
        fun `expression-based map keys are skipped (not constant)`() {
            // Keys that are computed expressions should be skipped since we can't
            // statically determine their value
            val code = """
                class Foo {
                    def test() {
                        def prefix = "key"
                        def m = [(prefix + "1"): 1, "static": 2]
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context, captureMapKeys = true)

            // Find the map declaration (it's the second one after 'prefix')
            val mapDecl = result.variables.find { it.name == "m" }
            assertNotNull(mapDecl)

            val keys = mapDecl!!.mapKeys!!
            // Only the static key should be extracted, the expression key should be skipped
            assertEquals(1, keys.size, "Expression-based key should be skipped")
            assertEquals("static", keys[0].key)
        }
    }

    // ==========================================
    // Line/Column Position Tests
    // ==========================================

    @Nested
    inner class PositionInformation {

        @Test
        fun `line numbers are 1-based from AST`() {
            val code = """
                class Foo {
                    def test() {
                        def first = 1
                        def second = 2
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            // Line numbers should be positive (1-based from AST)
            assertTrue(
                result.variables.all { it.line > 0 },
                "Line numbers should be positive (1-based)",
            )

            // Second declaration should be on a later line
            assertTrue(
                result.variables[1].line > result.variables[0].line,
                "Second declaration should be on a later line",
            )
        }
    }

    // ==========================================
    // Type Inference Edge Cases
    // ==========================================

    @Nested
    inner class TypeInference {

        @Test
        fun `explicitly typed declaration uses declared type`() {
            val code = """
                class Foo {
                    def test() {
                        String x = "hello"
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            assertEquals(TypeConstants.STRING, result.variables[0].inferredType)
        }

        @Test
        fun `declaration without initializer has dynamic type`() {
            // Note: This might not parse as a declaration in Groovy
            // Just checking what happens with edge cases
            val code = """
                class Foo {
                    def test() {
                        def x  // No initializer
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getMethodBlock(module, "test")

            val result = DeclarationWalker.walk(block, context)

            // Should handle gracefully - either no declaration or dynamic type
            if (result.variables.isNotEmpty()) {
                assertTrue(
                    result.variables[0].inferredType is SemanticType.Dynamic ||
                        result.variables[0].inferredType is SemanticType.Unknown,
                    "Uninitialized declaration should have dynamic or unknown type",
                )
            }
        }
    }

    // ==========================================
    // Script vs Class Context
    // ==========================================

    @Nested
    inner class ScriptContext {

        @Test
        fun `script-level declarations are captured`() {
            val code = """
                def x = 42
                def y = "hello"
            """.trimIndent()

            val module = parse(code)
            val context = getContext(module)
            val block = getScriptBlock(module)

            val result = DeclarationWalker.walk(block, context)

            assertEquals(2, result.variables.size)
            assertEquals("x", result.variables[0].name)
            assertEquals("y", result.variables[1].name)
        }
    }
}
