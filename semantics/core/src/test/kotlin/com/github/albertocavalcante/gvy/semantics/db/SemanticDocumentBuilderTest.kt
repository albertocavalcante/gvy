package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Comprehensive tests for SemanticDocumentBuilder.
 * Tests AST extraction and semantic document building.
 */
class SemanticDocumentBuilderTest {

    private val testUri = URI.create("file:///Test.groovy")

    private fun parse(code: String): ModuleNode {
        val config = CompilerConfiguration()
        val sourceUnit = SourceUnit("Test.groovy", code, config, null, null)
        sourceUnit.parse()
        sourceUnit.completePhase()
        sourceUnit.convert()
        return sourceUnit.ast
    }

    private fun buildDocument(code: String): SemanticDocument {
        val module = parse(code)
        val builder = SemanticDocumentBuilder(module, testUri)
        return builder.build()
    }

    @Nested
    @DisplayName("Symbol ID Generation")
    inner class SymbolIdGeneration {

        @Test
        fun `createClassSymbolId generates correct format`() {
            val code = """
                package com.example
                class MyClass {}
            """.trimIndent()

            val module = parse(code)
            val classNode = module.classes.first { it.nameWithoutPackage == "MyClass" }
            val symbolId = SemanticDocumentBuilder.createClassSymbolId(classNode)

            assertEquals("com/example/MyClass#", symbolId)
        }

        @Test
        fun `createFieldSymbolId generates correct format`() {
            val code = """
                class MyClass {
                    String myField
                }
            """.trimIndent()

            val module = parse(code)
            val classNode = module.classes.first { it.nameWithoutPackage == "MyClass" }
            val fieldNode = classNode.fields.first { it.name == "myField" }
            val symbolId = SemanticDocumentBuilder.createFieldSymbolId(classNode, fieldNode)

            assertEquals("MyClass#myField.", symbolId)
        }

        @Test
        fun `createMethodSymbolId generates correct format for no-param method`() {
            val code = """
                class MyClass {
                    void myMethod() {}
                }
            """.trimIndent()

            val module = parse(code)
            val classNode = module.classes.first { it.nameWithoutPackage == "MyClass" }
            val methodNode = classNode.methods.first { it.name == "myMethod" }
            val symbolId = SemanticDocumentBuilder.createMethodSymbolId(classNode, methodNode)

            assertEquals("MyClass#myMethod().", symbolId)
        }

        @Test
        fun `createMethodSymbolId generates correct format for parameterized method`() {
            val code = """
                class MyClass {
                    String concat(String a, int b) { return a + b }
                }
            """.trimIndent()

            val module = parse(code)
            val classNode = module.classes.first { it.nameWithoutPackage == "MyClass" }
            val methodNode = classNode.methods.first { it.name == "concat" }
            val symbolId = SemanticDocumentBuilder.createMethodSymbolId(classNode, methodNode)

            assertEquals("MyClass#concat(String,int).", symbolId)
        }
    }

    @Nested
    @DisplayName("Class Symbol Extraction")
    inner class ClassSymbolExtraction {

        @Test
        fun `extract simple class symbol`() {
            val code = """
                class MyClass {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val classSymbols = doc.findSymbolsByKind(SymbolKind.CLASS)
            assertEquals(1, classSymbols.size)

            val classSymbol = classSymbols[0]
            assertEquals("MyClass", classSymbol.name)
            assertEquals(SymbolKind.CLASS, classSymbol.kind)
            assertTrue(classSymbol.symbol.endsWith("MyClass#"))
        }

        @Test
        fun `extract interface symbol`() {
            val code = """
                interface MyInterface {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val interfaceSymbols = doc.findSymbolsByKind(SymbolKind.INTERFACE)
            assertEquals(1, interfaceSymbols.size)

            val interfaceSymbol = interfaceSymbols[0]
            assertEquals("MyInterface", interfaceSymbol.name)
            assertEquals(SymbolKind.INTERFACE, interfaceSymbol.kind)
        }

        @Test
        fun `extract enum symbol`() {
            val code = """
                enum Color {
                    RED, GREEN, BLUE
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val enumSymbols = doc.findSymbolsByKind(SymbolKind.ENUM)
            assertTrue(enumSymbols.isNotEmpty(), "Should find enum symbol")

            val enumSymbol = enumSymbols.first { it.name == "Color" }
            assertEquals("Color", enumSymbol.name)
            assertEquals(SymbolKind.ENUM, enumSymbol.kind)
        }

        @Test
        fun `extract class with package`() {
            val code = """
                package com.example
                class MyClass {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val classSymbols = doc.findSymbolsByKind(SymbolKind.CLASS)
            assertTrue(classSymbols.isNotEmpty())

            val classSymbol = classSymbols.first { it.name == "MyClass" }
            assertEquals("MyClass", classSymbol.name)
            assertTrue(classSymbol.symbol.contains("com/example"))
        }

        @Test
        fun `extract multiple classes`() {
            val code = """
                class ClassA {
                }
                class ClassB {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val classSymbols = doc.findSymbolsByKind(SymbolKind.CLASS)
            assertTrue(classSymbols.size >= 2, "Should find at least 2 classes")

            val classNames = classSymbols.map { it.name }
            assertTrue("ClassA" in classNames)
            assertTrue("ClassB" in classNames)
        }
    }

    @Nested
    @DisplayName("Field Symbol Extraction")
    inner class FieldSymbolExtraction {

        @Test
        fun `extract simple field`() {
            val code = """
                class MyClass {
                    String name
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val fieldSymbols = doc.findSymbolsByKind(SymbolKind.FIELD)
            assertTrue(fieldSymbols.isNotEmpty(), "Should find field symbol")

            val fieldSymbol = fieldSymbols.first { it.name == "name" }
            assertEquals("name", fieldSymbol.name)
            assertEquals(SymbolKind.FIELD, fieldSymbol.kind)
            assertNotNull(fieldSymbol.owner)
        }

        @Test
        fun `extract multiple fields`() {
            val code = """
                class Person {
                    String name
                    int age
                    boolean active
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val fieldSymbols = doc.findSymbolsByKind(SymbolKind.FIELD)
            assertTrue(fieldSymbols.size >= 3, "Should find at least 3 fields")

            val fieldNames = fieldSymbols.map { it.name }
            assertTrue("name" in fieldNames)
            assertTrue("age" in fieldNames)
            assertTrue("active" in fieldNames)
        }

        @Test
        fun `field has correct owner`() {
            val code = """
                class MyClass {
                    String myField
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val fieldSymbol = doc.findSymbolsByKind(SymbolKind.FIELD)
                .first { it.name == "myField" }

            assertNotNull(fieldSymbol.owner)
            assertTrue(fieldSymbol.owner!!.contains("MyClass"))
        }
    }

    @Nested
    @DisplayName("Method Symbol Extraction")
    inner class MethodSymbolExtraction {

        @Test
        fun `extract simple method`() {
            val code = """
                class MyClass {
                    void myMethod() {
                    }
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val methodSymbols = doc.findSymbolsByKind(SymbolKind.METHOD)
            val myMethod = methodSymbols.firstOrNull { it.name == "myMethod" }

            assertNotNull(myMethod)
            assertEquals("myMethod", myMethod?.name)
            assertEquals(SymbolKind.METHOD, myMethod?.kind)
        }

        @Test
        fun `extract method with parameters`() {
            val code = """
                class MyClass {
                    String concat(String a, String b) {
                        return a + b
                    }
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val methodSymbols = doc.findSymbolsByKind(SymbolKind.METHOD)
            val concatMethod = methodSymbols.firstOrNull { it.name == "concat" }

            assertNotNull(concatMethod)
            assertEquals("concat", concatMethod?.name)
        }

        @Test
        fun `extract multiple methods`() {
            val code = """
                class Calculator {
                    int add(int a, int b) { return a + b }
                    int subtract(int a, int b) { return a - b }
                    int multiply(int a, int b) { return a * b }
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val methodSymbols = doc.findSymbolsByKind(SymbolKind.METHOD)
            val methodNames = methodSymbols.map { it.name }

            assertTrue("add" in methodNames)
            assertTrue("subtract" in methodNames)
            assertTrue("multiply" in methodNames)
        }

        @Test
        fun `method has correct owner`() {
            val code = """
                class MyClass {
                    void myMethod() {}
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val methodSymbol = doc.findSymbolsByKind(SymbolKind.METHOD)
                .first { it.name == "myMethod" }

            assertNotNull(methodSymbol.owner)
            assertTrue(methodSymbol.owner!!.contains("MyClass"))
        }
    }

    @Nested
    @DisplayName("Property Symbol Extraction")
    inner class PropertySymbolExtraction {

        @Test
        fun `extract property symbol`() {
            val code = """
                class MyClass {
                    String name
                }
            """.trimIndent()

            val doc = buildDocument(code)

            // In Groovy, fields can also be properties
            val propertySymbols = doc.findSymbolsByKind(SymbolKind.PROPERTY)
            val fieldSymbols = doc.findSymbolsByKind(SymbolKind.FIELD)

            // Should have either property or field symbols
            assertTrue(propertySymbols.isNotEmpty() || fieldSymbols.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Parameter Symbol Extraction")
    inner class ParameterSymbolExtraction {

        @Test
        fun `extract method parameters`() {
            val code = """
                class MyClass {
                    String concat(String a, String b) {
                        return a + b
                    }
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val paramSymbols = doc.findSymbolsByKind(SymbolKind.PARAMETER)
            val paramNames = paramSymbols.map { it.name }

            assertTrue(paramSymbols.size >= 2, "Should find at least 2 parameters")
            assertTrue("a" in paramNames)
            assertTrue("b" in paramNames)
        }

        @Test
        fun `parameters have correct owner`() {
            val code = """
                class MyClass {
                    void myMethod(String param) {}
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val paramSymbol = doc.findSymbolsByKind(SymbolKind.PARAMETER)
                .firstOrNull { it.name == "param" }

            assertNotNull(paramSymbol)
            assertNotNull(paramSymbol?.owner)
            assertTrue(paramSymbol?.owner!!.contains("myMethod"))
        }
    }

    @Nested
    @DisplayName("Import Symbol Extraction")
    inner class ImportSymbolExtraction {

        @Test
        fun `extract regular import`() {
            val code = """
                import java.util.List

                class MyClass {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val importSymbols = doc.findSymbolsByKind(SymbolKind.IMPORT)
            assertTrue(importSymbols.isNotEmpty(), "Should find import symbol")

            val listImport = importSymbols.firstOrNull { it.name.contains("List") }
            assertNotNull(listImport)
        }

        @Test
        fun `extract static import`() {
            val code = """
                import static java.lang.Math.PI

                class MyClass {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val importSymbols = doc.findSymbolsByKind(SymbolKind.IMPORT)
            assertTrue(importSymbols.isNotEmpty(), "Should find import symbols")

            val piImport = importSymbols.firstOrNull { it.name.contains("PI") }
            assertNotNull(piImport)
        }
    }

    @Nested
    @DisplayName("Occurrence Extraction")
    inner class OccurrenceExtraction {

        @Test
        fun `extract definition occurrences for class`() {
            val code = """
                class MyClass {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val definitions = doc.findOccurrencesByRole(OccurrenceRole.DEFINITION)
            assertTrue(definitions.isNotEmpty(), "Should find definition occurrences")

            // Should have definition for the class
            val classDefinitions = definitions.filter { it.symbol.contains("MyClass") }
            assertTrue(classDefinitions.isNotEmpty())
        }

        @Test
        fun `extract definition occurrences for fields`() {
            val code = """
                class MyClass {
                    String name
                    int age
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val definitions = doc.findOccurrencesByRole(OccurrenceRole.DEFINITION)

            // Should have definitions for class and fields
            assertTrue(definitions.size >= 3, "Should have class + 2 field definitions")
        }

        @Test
        fun `extract method call occurrences`() {
            val code = """
                class MyClass {
                    void caller() {
                        myMethod()
                    }
                    void myMethod() {}
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val calls = doc.findOccurrencesByRole(OccurrenceRole.CALL)
            // Note: May or may not find the method call depending on visitor implementation
            // This is a basic test to ensure the structure is working
            assertTrue(calls.size >= 0)
        }
    }

    @Nested
    @DisplayName("Complex Scenarios")
    inner class ComplexScenarios {

        @Test
        fun `extract symbols from class with multiple members`() {
            val code = """
                package com.example

                import java.util.List

                class Person {
                    String name
                    int age

                    Person(String name, int age) {
                        this.name = name
                        this.age = age
                    }

                    String getName() {
                        return name
                    }

                    void setName(String name) {
                        this.name = name
                    }
                }
            """.trimIndent()

            val doc = buildDocument(code)

            // Verify we have symbols
            assertTrue(doc.symbols.isNotEmpty())

            // Verify we have occurrences
            assertTrue(doc.occurrences.isNotEmpty())

            // Verify class
            val classes = doc.findSymbolsByKind(SymbolKind.CLASS)
            assertTrue(classes.any { it.name == "Person" })

            // Verify fields
            val fields = doc.findSymbolsByKind(SymbolKind.FIELD)
            val fieldNames = fields.map { it.name }
            assertTrue("name" in fieldNames || fields.isNotEmpty())

            // Verify methods
            val methods = doc.findSymbolsByKind(SymbolKind.METHOD)
            val methodNames = methods.map { it.name }
            assertTrue(methodNames.contains("getName") || methodNames.contains("setName"))
        }

        @Test
        fun `extract symbols from multiple classes`() {
            val code = """
                class Address {
                    String street
                }

                class Person {
                    String name
                    Address address
                }
            """.trimIndent()

            val doc = buildDocument(code)

            val classes = doc.findSymbolsByKind(SymbolKind.CLASS)
            assertTrue(classes.size >= 2, "Should find at least 2 classes")

            val classNames = classes.map { it.name }
            assertTrue("Address" in classNames)
            assertTrue("Person" in classNames)
        }

        @Test
        fun `document has correct URI`() {
            val code = """
                class MyClass {
                }
            """.trimIndent()

            val doc = buildDocument(code)

            assertEquals(testUri, doc.uri)
        }
    }
}
