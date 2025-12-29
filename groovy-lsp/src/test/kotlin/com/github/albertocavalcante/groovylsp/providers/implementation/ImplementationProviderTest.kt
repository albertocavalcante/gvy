package com.github.albertocavalcante.groovylsp.providers.implementation

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class ImplementationProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var implementationProvider: ImplementationProvider

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        implementationProvider = ImplementationProvider(compilationService)
    }

    @Test
    fun `test find interface implementation - single class`() = runTest {
        // Arrange
        val content = """
            interface Shape {
                def area()
            }

            class Square implements Shape {
                def area() { return 100 }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content to build AST and symbol tables
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'Shape' interface at its declaration (line 0, column 10)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 10), // pointing at 'Shape' in interface declaration
        ).toList()

        // Assert
        assertFalse(implementations.isEmpty(), "Should find implementation for interface")
        assertEquals(1, implementations.size, "Should find exactly 1 implementation (Square)")

        // Implementation should point to Square class
        val impl = implementations[0]
        assertEquals(uri.toString(), impl.uri)
        assertEquals(4, impl.range.start.line, "Square class starts at line 4")
    }

    @Test
    fun `test find interface implementation - multiple classes`() = runTest {
        // Arrange
        val content = """
            interface Shape {
                def area()
            }

            class Square implements Shape {
                def area() { return 100 }
            }

            class Circle implements Shape {
                def area() { return 314 }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'Shape' interface
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 10),
        ).toList()

        // Assert
        assertEquals(2, implementations.size, "Should find 2 implementations (Square and Circle)")

        // Verify both classes are found
        val implLines = implementations.map { it.range.start.line }.sorted()
        assertEquals(listOf(4, 8), implLines, "Should find Square at line 4 and Circle at line 8")
    }

    @Test
    fun `test find method implementation in interface`() = runTest {
        // Arrange
        val content = """
            interface Shape {
                def area()
            }

            class Square implements Shape {
                def area() { return 100 }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'area()' method in interface (line 1, column 8)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(1, 8), // pointing at 'area' method in interface
        ).toList()

        // Assert
        assertFalse(implementations.isEmpty(), "Should find method implementation")
        assertEquals(1, implementations.size, "Should find 1 method implementation")

        // Implementation should point to Square.area() method
        val impl = implementations[0]
        assertEquals(uri.toString(), impl.uri)
        assertEquals(5, impl.range.start.line, "Square.area() method at line 5")
    }

    @Test
    fun `test find abstract method implementation`() = runTest {
        // Arrange
        val content = """
            abstract class Animal {
                abstract def makeSound()
            }

            class Dog extends Animal {
                def makeSound() { return "Woof" }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of abstract method 'makeSound' (line 1, column 17)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(1, 17), // pointing at 'makeSound' in abstract class
        ).toList()

        // Assert
        assertFalse(implementations.isEmpty(), "Should find abstract method implementation")
        assertEquals(1, implementations.size, "Should find 1 implementation")

        // Implementation should point to Dog.makeSound() method
        val impl = implementations[0]
        assertEquals(5, impl.range.start.line, "Dog.makeSound() method at line 5")
    }

    @Test
    fun `test concrete class has no implementations`() = runTest {
        // Arrange
        val content = """
            class ConcreteClass {
                def method() { return "test" }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Try to find implementations of concrete class (line 0, column 6)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 6), // pointing at 'ConcreteClass'
        ).toList()

        // Assert
        assertTrue(implementations.isEmpty(), "Concrete class should have no implementations")
    }

    @Test
    fun `test concrete method has no implementations`() = runTest {
        // Arrange
        val content = """
            class ConcreteClass {
                def concreteMethod() { return "test" }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Try to find implementations of concrete method (line 1, column 8)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(1, 8), // pointing at 'concreteMethod'
        ).toList()

        // Assert
        assertTrue(implementations.isEmpty(), "Concrete method should have no implementations")
    }

    @Test
    fun `test exclude abstract classes from implementations`() = runTest {
        // Arrange
        val content = """
            interface Shape {
                def area()
            }

            abstract class AbstractShape implements Shape {
                // Partial implementation
            }

            class Square implements Shape {
                def area() { return 100 }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'Shape' interface
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 10),
        ).toList()

        // Assert
        // Should find only Square, not AbstractShape
        assertEquals(1, implementations.size, "Should find only concrete implementation (Square)")
        val impl = implementations[0]
        assertEquals(8, impl.range.start.line, "Should find Square at line 8, not AbstractShape")
    }

    @Test
    fun `test method signature matching with parameters`() = runTest {
        // Arrange
        val content = """
            interface Calculator {
                def add(int a, int b)
            }

            class SimpleCalculator implements Calculator {
                def add(int a, int b) { return a + b }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'add' method with parameters
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(1, 8), // pointing at 'add' in interface
        ).toList()

        // Assert
        assertEquals(1, implementations.size, "Should find method with matching signature")
        val impl = implementations[0]
        assertEquals(5, impl.range.start.line, "Should find SimpleCalculator.add() at line 5")
    }

    @Test
    fun `test no implementations for interface without implementations`() = runTest {
        // Arrange
        val content = """
            interface Unused {
                def method()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of unused interface
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 10),
        ).toList()

        // Assert
        assertTrue(implementations.isEmpty(), "Should find no implementations for unused interface")
    }

    @Test
    fun `test implementations with invalid URI`() = runTest {
        // Act - Try to find implementations with invalid URI
        val implementations = implementationProvider.provideImplementations(
            "invalid-uri",
            Position(0, 0),
        ).toList()

        // Assert
        assertTrue(implementations.isEmpty(), "Should not find implementations with invalid URI")
    }

    @Test
    fun `test implementations without compilation`() = runTest {
        // Act - Try to find implementations for a file that hasn't been compiled
        val implementations = implementationProvider.provideImplementations(
            "file:///unknown.groovy",
            Position(0, 0),
        ).toList()

        // Assert
        assertTrue(implementations.isEmpty(), "Should not find implementations without compilation")
    }

    @Test
    fun `test implementations at position with no symbol`() = runTest {
        // Arrange
        val content = """
            interface Shape {
                def area()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Try to find implementations at a position with no symbol (in whitespace)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(2, 0), // empty line
        ).toList()

        // Assert
        assertTrue(
            implementations.isEmpty(),
            "Should not find implementations at position with no symbol",
        )
    }

    @Test
    fun `test interface with multiple methods - each method finds its implementation`() = runTest {
        // Arrange
        val content = """
            interface Shape {
                def area()
                def perimeter()
            }

            class Square implements Shape {
                def area() { return 100 }
                def perimeter() { return 40 }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'area()' method
        val areaImpls = implementationProvider.provideImplementations(
            uri.toString(),
            Position(1, 8), // pointing at 'area' in interface
        ).toList()

        // Act - Find implementations of 'perimeter()' method
        val perimeterImpls = implementationProvider.provideImplementations(
            uri.toString(),
            Position(2, 8), // pointing at 'perimeter' in interface
        ).toList()

        // Assert
        assertEquals(1, areaImpls.size, "Should find 1 implementation for area()")
        assertEquals(1, perimeterImpls.size, "Should find 1 implementation for perimeter()")

        assertEquals(6, areaImpls[0].range.start.line, "area() implementation at line 6")
        assertEquals(7, perimeterImpls[0].range.start.line, "perimeter() implementation at line 7")
    }

    @Test
    fun `test find implementation across multiple files`() = runTest {
        // Arrange - Create interface in one file
        val interfaceUri = URI.create("file:///interface.groovy")
        val interfaceContent = """
            interface Shape {
                def area()
            }
        """.trimIndent()

        val interfaceResult = compilationService.compile(interfaceUri, interfaceContent)
        assertTrue(interfaceResult.isSuccess, "Interface compilation should succeed")

        // Create implementation in a different file
        // Note: Use unqualified name - Groovy allows this even without classpath
        // The symbol table tracks interface names as strings, so matching works
        val implUri = URI.create("file:///impl.groovy")
        val implContent = """
            class Circle implements Shape {
                def area() { return 3.14 }
            }
        """.trimIndent()

        // Even if compilation has warnings about Shape not found,
        // the symbol table will still record that Circle implements "Shape"
        compilationService.compile(implUri, implContent)

        // Act - Find implementations of 'Shape' interface from interface file
        val implementations = implementationProvider.provideImplementations(
            interfaceUri.toString(),
            Position(0, 10), // pointing at 'Shape' in interface declaration
        ).toList()

        // Assert - Should find Circle in the different file
        assertEquals(1, implementations.size, "Should find implementation in different file")
        val impl = implementations[0]
        assertEquals(implUri.toString(), impl.uri, "Implementation should be in impl.groovy")
        assertEquals(0, impl.range.start.line, "Circle class starts at line 0")
    }

    @Test
    fun `test method signature matching with FQN vs simple name types`() = runTest {
        // Arrange - Interface uses FQN, implementation uses simple name
        val content = """
            interface Processor {
                def process(java.lang.String input)
            }
            
            class SimpleProcessor implements Processor {
                def process(String input) { return input }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act - Find implementations of 'process' method (with FQN parameter)
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(1, 8), // pointing at 'process' method in interface
        ).toList()

        // Assert - Should find SimpleProcessor.process despite String vs java.lang.String
        assertEquals(1, implementations.size, "Should match method despite FQN vs simple name difference")
        val impl = implementations[0]
        assertEquals(5, impl.range.start.line, "SimpleProcessor.process at line 5")
    }

    @Test
    fun `test find transitive interface implementation`() = runTest {
        // Arrange: B extends A, C implements B -> find C when looking for A
        val content = """
            interface A { def methodA() }
            interface B extends A { def methodB() }
            class C implements B {
                def methodA() { "A" }
                def methodB() { "B" }
            }
        """.trimIndent()

        val uri = URI.create("file:///TransitiveTest.groovy")
        compilationService.compile(uri, content)

        // Act: Find implementations of A
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 10), // On 'A'
        ).toList()

        // Assert: Should find C
        assertEquals(1, implementations.size, "Should find transitive implementation C")
        assertEquals(2, implementations[0].range.start.line, "Implementation should be class C")
    }

    @Test
    fun `test find superclass interface implementation`() = runTest {
        // Arrange: Shape implements Drawable, Circle extends Shape -> find Circle for Drawable
        val content = """
            interface Drawable { def draw() }
            abstract class Shape implements Drawable {
                abstract def area()
            }
            class Circle extends Shape {
                def draw() { "circle" }
                def area() { 3.14 }
            }
        """.trimIndent()

        val uri = URI.create("file:///SuperclassInterfaceTest.groovy")
        compilationService.compile(uri, content)

        // Act: Find implementations of Drawable
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 10), // On 'Drawable'
        ).toList()

        // Assert: Should find Circle (but NOT Shape because it's abstract)
        assertEquals(1, implementations.size, "Should find Circle via superclass interface")
        assertEquals(4, implementations[0].range.start.line, "Implementation should be class Circle")
    }

    @Test
    fun `test find abstract class implementation`() = runTest {
        // Arrange: Animal (abstract) <- Dog (concrete)
        val content = """
            abstract class Animal {
                abstract def makeSound()
            }
            class Dog extends Animal {
                def makeSound() { "Woof" }
            }
        """.trimIndent()

        val uri = URI.create("file:///AbstractClassTest.groovy")
        compilationService.compile(uri, content)

        // Act: Find implementations of Animal
        val implementations = implementationProvider.provideImplementations(
            uri.toString(),
            Position(0, 15), // On 'Animal'
        ).toList()

        // Assert: Should find Dog
        assertEquals(1, implementations.size, "Should find implementation Dog for abstract class Animal")
        assertEquals(3, implementations[0].range.start.line, "Implementation should be class Dog")
    }
}
