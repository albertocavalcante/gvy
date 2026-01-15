package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for NativeTypeContext cross-file type resolution using WorkspaceMemberLookup.
 * This tests Phase 3 enhancement to resolve fields and methods from other workspace files.
 */
class NativeTypeContextCrossFileTest {

    private val parser = GroovyParserFacade()

    private val stubSolver = object : TypeSolver {
        override var parent: TypeSolver? = null
        override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> = SymbolReference.unsolved()
    }

    private fun parse(code: String): ModuleNode {
        // Use CANONICALIZATION phase for proper type resolution (e.g., String -> java.lang.String)
        val request = ParseRequest(
            URI.create("file:///Test.groovy"),
            code,
            compilePhase = Phases.CANONICALIZATION,
        )
        val result = parser.parse(request)
        if (!result.isSuccessful) {
            error("Parse failed: " + result.diagnostics)
        }
        return result.ast!!
    }

    @Nested
    @DisplayName("Cross-file Field Resolution")
    inner class CrossFileFieldResolution {

        @Test
        fun `resolves field type from workspace index when not in same module`() {
            // File 1: Person class with name field (not in current module)
            // File 2: Code trying to access Person.name
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            // Create mock workspace lookup that simulates finding Person.name in another file
            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? =
                    if (classFqn == "Person" && fieldName == "name") {
                        MemberInfo(
                            name = "name",
                            kind = SymbolKind.FIELD,
                            type = TypeConstants.STRING,
                            signature = null,
                            symbolId = "Person#name.",
                        )
                    } else {
                        null
                    }

                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? = null
                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            // Create context with workspace lookup
            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            // Should find the field from workspace index
            val fieldType = context.getFieldType(personType, "name")

            assertNotNull(fieldType, "Should resolve field type from workspace index")
            assertTrue(fieldType is SemanticType.Known, "Should be Known type")
            assertEquals(TypeConstants.STRING, fieldType)
        }

        @Test
        fun `resolves field type from workspace index for primitive types`() {
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? =
                    if (classFqn == "Person" && fieldName == "age") {
                        MemberInfo(
                            name = "age",
                            kind = SymbolKind.FIELD,
                            type = TypeConstants.INT,
                            signature = null,
                            symbolId = "Person#age.",
                        )
                    } else {
                        null
                    }

                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? = null
                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val fieldType = context.getFieldType(personType, "age")

            assertNotNull(fieldType)
            assertEquals(TypeConstants.INT, fieldType)
        }

        @Test
        fun `resolves field type from workspace index for custom class types`() {
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? =
                    if (classFqn == "Person" && fieldName == "address") {
                        MemberInfo(
                            name = "address",
                            kind = SymbolKind.FIELD,
                            type = SemanticType.Known("Address"),
                            signature = null,
                            symbolId = "Person#address.",
                        )
                    } else {
                        null
                    }

                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? = null
                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val fieldType = context.getFieldType(personType, "address")

            assertNotNull(fieldType)
            assertTrue(fieldType is SemanticType.Known)
            assertEquals("Address", fieldType.fqn)
        }

        @Test
        fun `falls back to Unknown when field not found in workspace index`() {
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? = null
                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? = null
                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val fieldType = context.getFieldType(personType, "nonExistentField")

            assertNotNull(fieldType)
            assertTrue(fieldType is SemanticType.Unknown)
        }

        @Test
        fun `prefers same-module resolution over workspace index`() {
            // When a field exists in the same module, it should be resolved first
            // before checking the workspace index
            val code = """
                class Person {
                    String name
                }

                class Consumer {
                    void test() {
                        // Person is in same module
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            // Workspace lookup returns a different type (Integer) to verify same-module takes precedence
            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? =
                    if (classFqn == "Person" && fieldName == "name") {
                        MemberInfo(
                            name = "name",
                            kind = SymbolKind.FIELD,
                            type = TypeConstants.INT, // Wrong type to test precedence
                            signature = null,
                            symbolId = "Person#name.",
                        )
                    } else {
                        null
                    }

                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? = null
                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val fieldType = context.getFieldType(personType, "name")

            assertNotNull(fieldType)
            // Should use same-module resolution (String), not workspace (Int)
            assertEquals(TypeConstants.STRING, fieldType)
        }

        @Test
        fun `works without workspace index (backward compatibility)`() {
            // When no workspace index is provided, should work as before
            val code = """
                class Person {
                    String name
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            // Context without workspace lookup (null)
            val context = getTypeContext(module, semantics)
            val personType = SemanticType.Known("Person")

            val fieldType = context.getFieldType(personType, "name")

            assertNotNull(fieldType)
            assertEquals(TypeConstants.STRING, fieldType)
        }
    }

    @Nested
    @DisplayName("Cross-file Method Resolution")
    inner class CrossFileMethodResolution {

        @Test
        fun `resolves method return type from workspace index when not in same module`() {
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? = null
                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
                    if (classFqn == "Person" && methodName == "getName" && arity == 0) {
                        MemberInfo(
                            name = "getName",
                            kind = SymbolKind.METHOD,
                            type = TypeConstants.STRING,
                            signature = "Person#getName().",
                            symbolId = "Person#getName().",
                        )
                    } else {
                        null
                    }

                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val returnType = context.getMethodReturnType(personType, "getName", emptyList())

            assertNotNull(returnType)
            assertEquals(TypeConstants.STRING, returnType)
        }

        @Test
        fun `resolves method return type for methods with parameters`() {
            val code = """
                class Consumer {
                    void test() {
                        // Calculator class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? = null
                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
                    if (classFqn == "Calculator" && methodName == "add" && arity == 2) {
                        MemberInfo(
                            name = "add",
                            kind = SymbolKind.METHOD,
                            type = TypeConstants.INT,
                            signature = "Calculator#add(int,int).",
                            symbolId = "Calculator#add(int,int).",
                        )
                    } else {
                        null
                    }

                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val calcType = SemanticType.Known("Calculator")

            val returnType = context.getMethodReturnType(
                calcType,
                "add",
                listOf(TypeConstants.INT, TypeConstants.INT),
            )

            assertNotNull(returnType)
            assertEquals(TypeConstants.INT, returnType)
        }

        @Test
        fun `resolves method return type for custom class returns`() {
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? = null
                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
                    if (classFqn == "Person" && methodName == "getAddress" && arity == 0) {
                        MemberInfo(
                            name = "getAddress",
                            kind = SymbolKind.METHOD,
                            type = SemanticType.Known("Address"),
                            signature = "Person#getAddress().",
                            symbolId = "Person#getAddress().",
                        )
                    } else {
                        null
                    }

                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val returnType = context.getMethodReturnType(personType, "getAddress", emptyList())

            assertNotNull(returnType)
            assertTrue(returnType is SemanticType.Known)
            assertEquals("Address", returnType.fqn)
        }

        @Test
        fun `falls back to Unknown when method not found in workspace index`() {
            val code = """
                class Consumer {
                    void test() {
                        // Person class is in another file
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? = null
                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? = null
                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val returnType = context.getMethodReturnType(personType, "nonExistentMethod", emptyList())

            assertNotNull(returnType)
            assertTrue(returnType is SemanticType.Unknown)
        }

        @Test
        fun `prefers same-module resolution over workspace index for methods`() {
            val code = """
                class Person {
                    String getName() {
                        return "test"
                    }
                }

                class Consumer {
                    void test() {
                        // Person is in same module
                    }
                }
            """.trimIndent()

            val module = parse(code)
            val semantics = GroovySemantics(stubSolver)
            semantics.inject(module)

            // Workspace lookup returns a different type to verify same-module takes precedence
            val workspaceLookup = object : MemberLookup {
                override fun findField(classFqn: String, fieldName: String): MemberInfo? = null
                override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
                    if (classFqn == "Person" && methodName == "getName" && arity == 0) {
                        MemberInfo(
                            name = "getName",
                            kind = SymbolKind.METHOD,
                            type = TypeConstants.INT, // Wrong type to test precedence
                            signature = "Person#getName().",
                            symbolId = "Person#getName().",
                        )
                    } else {
                        null
                    }

                override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> = emptyList()
            }

            val context = getTypeContextWithWorkspace(module, semantics, workspaceLookup)
            val personType = SemanticType.Known("Person")

            val returnType = context.getMethodReturnType(personType, "getName", emptyList())

            assertNotNull(returnType)
            // Should use same-module resolution (String), not workspace (Int)
            assertEquals(TypeConstants.STRING, returnType)
        }
    }

    // Helper function to access internal context cache
    private fun getTypeContext(module: ModuleNode, semantics: GroovySemantics): NativeTypeContext = try {
        val contextCacheField = GroovySemantics::class.java.getDeclaredField("contextCache")
        contextCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = contextCacheField.get(
            semantics,
        ) as java.util.concurrent.ConcurrentHashMap<ModuleNode, NativeTypeContext>
        cache[module] ?: throw IllegalStateException("Context not found for module after injection")
    } catch (e: NoSuchFieldException) {
        throw IllegalStateException("Could not access contextCache field", e)
    }

    // Helper function to create a context with workspace lookup
    private fun getTypeContextWithWorkspace(
        module: ModuleNode,
        semantics: GroovySemantics,
        workspaceLookup: MemberLookup,
    ): NativeTypeContext {
        // Get the calculator registry from semantics
        val calculatorRegistryField = GroovySemantics::class.java.getDeclaredField("calculatorRegistry")
        calculatorRegistryField.isAccessible = true
        val calculatorRegistry = calculatorRegistryField.get(semantics)
            as com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry

        // Create a new scope for the module
        val scope = NativeScope.fromModule(module)

        // Create context with workspace lookup
        return NativeTypeContext(
            typeSolver = stubSolver,
            calculatorRegistry = calculatorRegistry,
            scope = scope,
            isStaticCompilation = false,
            workspaceMemberLookup = workspaceLookup,
        )
    }
}
