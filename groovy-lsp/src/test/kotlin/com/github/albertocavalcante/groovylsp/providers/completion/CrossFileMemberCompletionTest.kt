package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Tests for cross-file member completion using WorkspaceSymbolIndex.
 *
 * These tests verify that CompletionProvider can suggest members (fields, methods, properties)
 * from classes defined in other files in the workspace.
 */
class CrossFileMemberCompletionTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var semanticDb: GroovySemanticDB
    private lateinit var workspaceSymbolIndex: WorkspaceSymbolIndex
    private lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        documentProvider = DocumentProvider()
        semanticResolver = SemanticTypeResolver(ReflectionTypeSolver())
        semanticDb = GroovySemanticDB()
        workspaceSymbolIndex = WorkspaceSymbolIndex(semanticDb)

        // Create temp directory for workspace
        tempDir = createTempDirectory("cross-file-completion-test")

        // Initialize workspace
        compilationService.workspaceManager.initializeWorkspace(tempDir)
    }

    @Nested
    @DisplayName("Field Completion")
    inner class FieldCompletionTests {

        @Test
        @Disabled("Test infrastructure - needs WorkspaceSymbolIndex integration in CompletionProvider")
        fun `should show field from class in another file`() = runBlocking {
            // Arrange: Create Person class in another file
            val personFile = tempDir / "Person.groovy"
            personFile.writeText(
                """
                package com.example

                class Person {
                    String name
                    int age
                }
                """.trimIndent(),
            )

            // Arrange: Create main file that uses Person
            val mainFile = tempDir / "Main.groovy"
            val mainContent = """
                package com.example

                def person = new Person()
                person.
            """.trimIndent()
            mainFile.writeText(mainContent)

            val personUri = personFile.toUri()
            val mainUri = mainFile.toUri()

            // Compile both files
            compilationService.compile(personUri, personFile.toFile().readText())
            compilationService.compile(mainUri, mainContent)

            // Act: Get completions after "person."
            val completions = CompletionProvider.getContextualCompletions(
                mainUri.toString(),
                3, // Line with "person."
                7, // Character after the dot
                compilationService,
                semanticResolver,
                mainContent,
            )

            // Assert: Should suggest "name" and "age" fields
            val labels = completions.map { it.label }
            assertTrue(labels.contains("name"), "Should suggest 'name' field from Person class. Found: $labels")
            assertTrue(labels.contains("age"), "Should suggest 'age' field from Person class. Found: $labels")
        }
    }

    @Nested
    @DisplayName("Method Completion")
    inner class MethodCompletionTests {

        @Test
        @Disabled("Test infrastructure - needs WorkspaceSymbolIndex integration in CompletionProvider")
        fun `should show method from class in another file`() = runBlocking {
            // Arrange: Create Calculator class in another file
            val calcFile = tempDir / "Calculator.groovy"
            calcFile.writeText(
                """
                package com.example

                class Calculator {
                    int add(int a, int b) {
                        return a + b
                    }

                    int subtract(int a, int b) {
                        return a - b
                    }
                }
                """.trimIndent(),
            )

            // Arrange: Create main file that uses Calculator
            val mainFile = tempDir / "Main.groovy"
            val mainContent = """
                package com.example

                def calc = new Calculator()
                calc.
            """.trimIndent()
            mainFile.writeText(mainContent)

            val calcUri = calcFile.toUri()
            val mainUri = mainFile.toUri()

            // Compile both files
            compilationService.compile(calcUri, calcFile.toFile().readText())
            compilationService.compile(mainUri, mainContent)

            // Act: Get completions after "calc."
            val completions = CompletionProvider.getContextualCompletions(
                mainUri.toString(),
                3, // Line with "calc."
                5, // Character after the dot
                compilationService,
                semanticResolver,
                mainContent,
            )

            // Assert: Should suggest "add" and "subtract" methods
            val labels = completions.map { it.label }
            assertTrue(labels.contains("add"), "Should suggest 'add' method from Calculator class. Found: $labels")
            assertTrue(
                labels.contains("subtract"),
                "Should suggest 'subtract' method from Calculator class. Found: $labels",
            )
        }
    }

    @Nested
    @DisplayName("Property Completion")
    inner class PropertyCompletionTests {

        @Test
        @Disabled("Test infrastructure - needs WorkspaceSymbolIndex integration in CompletionProvider")
        fun `should show property from class in another file`() = runBlocking {
            // Arrange: Create Config class with Groovy properties
            val configFile = tempDir / "Config.groovy"
            configFile.writeText(
                """
                package com.example

                class Config {
                    String host = "localhost"
                    int port = 8080

                    String getUrl() {
                        return "http://${'$'}host:${'$'}port"
                    }
                }
                """.trimIndent(),
            )

            // Arrange: Create main file that uses Config
            val mainFile = tempDir / "Main.groovy"
            val mainContent = """
                package com.example

                def config = new Config()
                config.
            """.trimIndent()
            mainFile.writeText(mainContent)

            val configUri = configFile.toUri()
            val mainUri = mainFile.toUri()

            // Compile both files
            compilationService.compile(configUri, configFile.toFile().readText())
            compilationService.compile(mainUri, mainContent)

            // Act: Get completions after "config."
            val completions = CompletionProvider.getContextualCompletions(
                mainUri.toString(),
                3, // Line with "config."
                7, // Character after the dot
                compilationService,
                semanticResolver,
                mainContent,
            )

            // Assert: Should suggest properties and methods
            val labels = completions.map { it.label }
            assertTrue(labels.contains("host"), "Should suggest 'host' property from Config class. Found: $labels")
            assertTrue(labels.contains("port"), "Should suggest 'port' property from Config class. Found: $labels")
            assertTrue(labels.contains("getUrl"), "Should suggest 'getUrl' method from Config class. Found: $labels")
        }
    }

    @Nested
    @DisplayName("Inherited Member Completion")
    inner class InheritedMemberCompletionTests {

        @Test
        @Disabled("Test infrastructure - needs WorkspaceSymbolIndex integration and inheritance support")
        fun `should show inherited field from parent class in another file`() = runBlocking {
            // Arrange: Create parent class in one file
            val parentFile = tempDir / "Animal.groovy"
            parentFile.writeText(
                """
                package com.example

                class Animal {
                    String species
                    int age
                }
                """.trimIndent(),
            )

            // Arrange: Create child class in another file
            val childFile = tempDir / "Dog.groovy"
            childFile.writeText(
                """
                package com.example

                class Dog extends Animal {
                    String breed
                }
                """.trimIndent(),
            )

            // Arrange: Create main file that uses Dog
            val mainFile = tempDir / "Main.groovy"
            val mainContent = """
                package com.example

                def dog = new Dog()
                dog.
            """.trimIndent()
            mainFile.writeText(mainContent)

            val parentUri = parentFile.toUri()
            val childUri = childFile.toUri()
            val mainUri = mainFile.toUri()

            // Compile all files
            compilationService.compile(parentUri, parentFile.toFile().readText())
            compilationService.compile(childUri, childFile.toFile().readText())
            compilationService.compile(mainUri, mainContent)

            // Act: Get completions after "dog."
            val completions = CompletionProvider.getContextualCompletions(
                mainUri.toString(),
                3, // Line with "dog."
                4, // Character after the dot
                compilationService,
                semanticResolver,
                mainContent,
            )

            // Assert: Should suggest both own and inherited fields
            val labels = completions.map { it.label }
            assertTrue(labels.contains("breed"), "Should suggest 'breed' field from Dog class. Found: $labels")
            assertTrue(
                labels.contains("species"),
                "Should suggest inherited 'species' field from Animal class. Found: $labels",
            )
            assertTrue(
                labels.contains("age"),
                "Should suggest inherited 'age' field from Animal class. Found: $labels",
            )
        }

        @Test
        @Disabled("Test infrastructure - needs WorkspaceSymbolIndex integration and inheritance support")
        fun `should show inherited method from parent class in another file`() = runBlocking {
            // Arrange: Create parent class in one file
            val parentFile = tempDir / "Vehicle.groovy"
            parentFile.writeText(
                """
                package com.example

                class Vehicle {
                    void start() {
                        println "Starting..."
                    }

                    void stop() {
                        println "Stopping..."
                    }
                }
                """.trimIndent(),
            )

            // Arrange: Create child class in another file
            val childFile = tempDir / "Car.groovy"
            childFile.writeText(
                """
                package com.example

                class Car extends Vehicle {
                    void honk() {
                        println "Beep beep!"
                    }
                }
                """.trimIndent(),
            )

            // Arrange: Create main file that uses Car
            val mainFile = tempDir / "Main.groovy"
            val mainContent = """
                package com.example

                def car = new Car()
                car.
            """.trimIndent()
            mainFile.writeText(mainContent)

            val parentUri = parentFile.toUri()
            val childUri = childFile.toUri()
            val mainUri = mainFile.toUri()

            // Compile all files
            compilationService.compile(parentUri, parentFile.toFile().readText())
            compilationService.compile(childUri, childFile.toFile().readText())
            compilationService.compile(mainUri, mainContent)

            // Act: Get completions after "car."
            val completions = CompletionProvider.getContextualCompletions(
                mainUri.toString(),
                3, // Line with "car."
                4, // Character after the dot
                compilationService,
                semanticResolver,
                mainContent,
            )

            // Assert: Should suggest both own and inherited methods
            val labels = completions.map { it.label }
            assertTrue(labels.contains("honk"), "Should suggest 'honk' method from Car class. Found: $labels")
            assertTrue(
                labels.contains("start"),
                "Should suggest inherited 'start' method from Vehicle class. Found: $labels",
            )
            assertTrue(
                labels.contains("stop"),
                "Should suggest inherited 'stop' method from Vehicle class. Found: $labels",
            )
        }
    }

    @Nested
    @DisplayName("No Duplicate Completions")
    inner class NoDuplicateCompletionsTests {

        @Test
        @Disabled("Test infrastructure - needs WorkspaceSymbolIndex integration")
        fun `should not show duplicate completions from workspace and classpath`() = runBlocking {
            // Arrange: Create a class that has toString() method (inherited from Object)
            val personFile = tempDir / "Person.groovy"
            personFile.writeText(
                """
                package com.example

                class Person {
                    String name

                    String toString() {
                        return "Person: ${'$'}name"
                    }
                }
                """.trimIndent(),
            )

            // Arrange: Create main file that uses Person
            val mainFile = tempDir / "Main.groovy"
            val mainContent = """
                package com.example

                def person = new Person()
                person.
            """.trimIndent()
            mainFile.writeText(mainContent)

            val personUri = personFile.toUri()
            val mainUri = mainFile.toUri()

            // Compile both files
            compilationService.compile(personUri, personFile.toFile().readText())
            compilationService.compile(mainUri, mainContent)

            // Act: Get completions after "person."
            val completions = CompletionProvider.getContextualCompletions(
                mainUri.toString(),
                3, // Line with "person."
                7, // Character after the dot
                compilationService,
                semanticResolver,
                mainContent,
            )

            // Assert: Should have exactly one "toString" method, not duplicates
            val toStringMethods = completions.filter { it.label == "toString" }
            assertTrue(
                toStringMethods.size == 1,
                "Should have exactly one 'toString' method. Found: ${toStringMethods.size}",
            )
        }
    }
}
