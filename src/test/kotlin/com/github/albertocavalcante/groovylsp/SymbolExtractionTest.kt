package com.github.albertocavalcante.groovylsp
import com.github.albertocavalcante.groovylsp.ast.ClassSymbol
import com.github.albertocavalcante.groovylsp.ast.FieldSymbol
import com.github.albertocavalcante.groovylsp.ast.ImportSymbol
import com.github.albertocavalcante.groovylsp.ast.MethodSymbol
import com.github.albertocavalcante.groovylsp.ast.SymbolExtractor
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test-driven development for AST symbol extraction.
 * These tests drive the implementation of real IDE features like completion and go-to-definition.
 */
class SymbolExtractionTest {

    private lateinit var compilationService: GroovyCompilationService

    @BeforeEach
    fun setup() {
        compilationService = TestUtils.createCompilationService()
    }

    @Test
    fun `extract class names from simple groovy file`() = runTest {
        val content = """
            package com.example

            class Person {
                String name
                int age
            }

            class Company {
                String name
                List<Person> employees
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val result = compilationService.compile(uri, content)

        assertTrue(result.isSuccess)
        assertNotNull(result.ast)

        // Extract class symbols - this should drive implementation
        val classSymbols = extractClassSymbols(result.ast!!)

        assertEquals(2, classSymbols.size)

        val person = classSymbols.find { it.name == "Person" }
        assertNotNull(person)
        assertEquals("com.example", person.packageName)

        val company = classSymbols.find { it.name == "Company" }
        assertNotNull(company)
        assertEquals("com.example", company.packageName)
    }

    @Test
    fun `extract method signatures from class`() = runTest {
        val content = """
            class Calculator {
                int add(int a, int b) {
                    return a + b
                }

                double multiply(double x, double y) {
                    return x * y
                }

                void printResult(String message) {
                    println message
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///Calculator.groovy")
        val result = compilationService.compile(uri, content)

        assertTrue(result.isSuccess)
        assertNotNull(result.ast)

        val classSymbols = extractClassSymbols(result.ast!!)
        assertEquals(1, classSymbols.size)

        val calculator = classSymbols.first()
        assertEquals("Calculator", calculator.name)

        // Extract method symbols
        val methods = extractMethodSymbols(calculator.astNode)
        assertEquals(3, methods.size)

        val addMethod = methods.find { it.name == "add" }
        assertNotNull(addMethod)
        assertEquals("int", addMethod.returnType)
        assertEquals(2, addMethod.parameters.size)
        assertEquals("int", addMethod.parameters[0].type)
        assertEquals("a", addMethod.parameters[0].name)

        val multiplyMethod = methods.find { it.name == "multiply" }
        assertNotNull(multiplyMethod)
        assertEquals("double", multiplyMethod.returnType)

        val printMethod = methods.find { it.name == "printResult" }
        assertNotNull(printMethod)
        assertEquals("void", printMethod.returnType)
        assertEquals(1, printMethod.parameters.size)
    }

    @Test
    fun `extract field declarations from class`() = runTest {
        val content = """
            class DataModel {
                private String id
                public int count = 0
                protected List<String> items
                static final String VERSION = "1.0"

                // Property-style fields (Groovy-specific)
                String name
                boolean active = true
            }
        """.trimIndent()

        val uri = URI.create("file:///DataModel.groovy")
        val result = compilationService.compile(uri, content)

        assertTrue(result.isSuccess)
        assertNotNull(result.ast)

        val classSymbols = extractClassSymbols(result.ast!!)
        val dataModel = classSymbols.first()

        val fields = extractFieldSymbols(dataModel.astNode)
        assertTrue(fields.size >= 4) // At least the explicitly typed fields

        val idField = fields.find { it.name == "id" }
        assertNotNull(idField)
        assertEquals("String", idField.type)
        assertTrue(idField.isPrivate)

        val countField = fields.find { it.name == "count" }
        assertNotNull(countField)
        assertEquals("int", countField.type)
        assertTrue(countField.isPublic)

        val versionField = fields.find { it.name == "VERSION" }
        assertNotNull(versionField)
        assertEquals("String", versionField.type)
        assertTrue(versionField.isStatic)
        assertTrue(versionField.isFinal)
    }

    @Test
    fun `extract symbols from import statements`() = runTest {
        val content = """
            package com.example.services

            import java.util.*
            import java.util.concurrent.ConcurrentHashMap
            import groovy.transform.CompileStatic
            import static java.lang.Math.PI
            import static java.util.Collections.emptyList

            @CompileStatic
            class ServiceManager {
                private Map<String, Object> services = new ConcurrentHashMap<>()
            }
        """.trimIndent()

        val uri = URI.create("file:///ServiceManager.groovy")
        val result = compilationService.compile(uri, content)

        assertTrue(result.isSuccess)
        assertNotNull(result.ast)

        val importSymbols = extractImportSymbols(result.ast!!)
        assertTrue(importSymbols.size >= 4)

        val utilImport = importSymbols.find { it.packageName == "java.util" && it.isStarImport }
        assertNotNull(utilImport)

        val mapImport = importSymbols.find { it.className == "ConcurrentHashMap" }
        assertNotNull(mapImport)
        assertEquals("java.util.concurrent", mapImport.packageName)

        val staticImports = importSymbols.filter { it.isStatic }
        assertTrue(staticImports.size >= 2)
    }

    // Using the actual SymbolExtractor implementation
    private fun extractClassSymbols(ast: Any): List<ClassSymbol> = SymbolExtractor.extractClassSymbols(ast)

    private fun extractMethodSymbols(classNode: Any): List<MethodSymbol> =
        SymbolExtractor.extractMethodSymbols(classNode)

    private fun extractFieldSymbols(classNode: Any): List<FieldSymbol> = SymbolExtractor.extractFieldSymbols(classNode)

    private fun extractImportSymbols(ast: Any): List<ImportSymbol> = SymbolExtractor.extractImportSymbols(ast)
}
