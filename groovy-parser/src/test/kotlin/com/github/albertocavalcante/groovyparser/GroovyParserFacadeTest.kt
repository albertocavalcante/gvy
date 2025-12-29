package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroovyParserFacadeTest {

    private val parser = GroovyParserFacade()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parse valid groovy snippet`() {
        val code = """
            class Greeting {
                String message() { "hi" }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Greeting.groovy"),
                content = code,
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)
        assertEquals(0, result.diagnostics.size)
    }

    @Test
    fun `parse invalid groovy snippet emits diagnostics`() {
        val code = """
            class Broken {
                void brokenMethod( {
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Broken.groovy"),
                content = code,
            ),
        )

        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.any { it.severity == ParserSeverity.ERROR })
    }

    @Test
    fun `workspace sources are added to compilation unit`() {
        val extraSource = tempDir.resolve("Extra.groovy").toFile()
        extraSource.writeText(
            """
            class ExtraSource {
                String value = "ok"
            }
            """.trimIndent(),
        )

        val code = """
            class UsesExtra {
                ExtraSource ref = new ExtraSource()
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///UsesExtra.groovy"),
                content = code,
                workspaceSources = listOf(extraSource.toPath()),
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)
        assertEquals(0, result.diagnostics.size)
    }

    @Test
    fun `parse populates recursive ast model by default`() {
        val code = """
            class Sample {
                def method(int x) {
                    if (x > 0) {
                        println x
                    }
                }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Sample.groovy"),
                content = code,
            ),
        )

        assertTrue(result.isSuccessful)
        assertTrue(result.astModel is RecursiveAstVisitor, "AST model should be RecursiveAstVisitor")
        val recursiveNodes = result.astModel.getAllNodes()
        assertTrue(recursiveNodes.isNotEmpty(), "AST model should collect nodes")
        val classCount = recursiveNodes.count { it is org.codehaus.groovy.ast.ClassNode }
        assertTrue(classCount >= 1, "Recursive visitor should track class nodes")
    }

    @Test
    fun `astModel provides nodes at common positions`() {
        val code = """
            class Sample {
              def method(int x) {
                if (x > 0) {
                  println x
                }
              }
            }
        """.trimIndent()
        val uri = URI.create("file:///Sample.groovy")

        val result = parser.parse(
            ParseRequest(
                uri = uri,
                content = code,
            ),
        )

        val positions = listOf(
            com.github.albertocavalcante.groovyparser.ast.types.Position(0, 6) to "class name",
            com.github.albertocavalcante.groovyparser.ast.types.Position(1, 6) to "method name",
            com.github.albertocavalcante.groovyparser.ast.types.Position(1, 17) to "parameter",
            com.github.albertocavalcante.groovyparser.ast.types.Position(2, 8) to "if condition variable",
            com.github.albertocavalcante.groovyparser.ast.types.Position(3, 14) to "println argument",
        )

        positions.forEach { (pos, label) ->
            val node = result.astModel.getNodeAt(uri, pos)
            assertNotNull(node, "AST model missing $label at $pos")
        }
    }

    @Test
    fun `parse succeeds when classpath contains AST transformation service files`() {
        // Create a directory structure mimicking a project with AST transformations
        val classpathDir = tempDir.resolve("classpath-with-transforms")
        Files.createDirectories(classpathDir)

        // Create META-INF/services directory
        val servicesDir = classpathDir.resolve("META-INF/services")
        Files.createDirectories(servicesDir)

        // Create the AST transformation service file with fake transformation names
        // These are class names that DON'T exist - previously this would cause NoClassDefFoundError
        val serviceFile = servicesDir.resolve("org.codehaus.groovy.transform.ASTTransformation")
        Files.writeString(
            serviceFile,
            """
            # This is a comment - should be ignored
            com.example.fake.NonExistentTransform
            com.example.another.FakeAstTransformation
            """.trimIndent(),
        )

        val code = """
            class SimpleClass {
                String name = "test"
            }
        """.trimIndent()

        // Parse with the fake transformation classpath
        // Before the fix, this would throw NoClassDefFoundError
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///SimpleClass.groovy"),
                content = code,
                classpath = listOf(classpathDir),
            ),
        )

        // Parsing should succeed - the transformations are disabled, not loaded
        assertTrue(result.isSuccessful, "Parse should succeed with transformation service files on classpath")
        assertNotNull(result.ast, "AST should be populated")
        assertEquals(0, result.diagnostics.size, "No diagnostics expected")
    }

    @Test
    fun `parse succeeds when classpath contains JAR with AST transformation service file`() {
        // Create a JAR file containing AST transformation service file
        val jarPath = tempDir.resolve("transforms.jar")

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            // Add the service file entry
            val entry = JarEntry("META-INF/services/org.codehaus.groovy.transform.ASTTransformation")
            jos.putNextEntry(entry)
            jos.write("com.example.fake.JarTransform\n".toByteArray())
            jos.closeEntry()
        }

        val code = """
            class JarTestClass {
                int value = 42
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///JarTestClass.groovy"),
                content = code,
                classpath = listOf(jarPath),
            ),
        )

        assertTrue(result.isSuccessful, "Parse should succeed with JAR containing transformation service file")
        assertNotNull(result.ast, "AST should be populated")
        assertEquals(0, result.diagnostics.size, "No diagnostics expected")
    }

    @Test
    fun `parse succeeds for class extending custom superclass with AST transforms on classpath`() {
        // This test simulates the Spock scenario:
        // - Class extends a superclass that may not be on the classpath (e.g., spock.lang.Specification)
        // - Classpath contains AST transformation service files
        // Previously this would cause NoClassDefFoundError when Groovy tried to load transformations

        // Create a classpath directory with AST transformation service file
        val classpathDir = tempDir.resolve("spock-like-classpath")
        Files.createDirectories(classpathDir)

        val servicesDir = classpathDir.resolve("META-INF/services")
        Files.createDirectories(servicesDir)

        val serviceFile = servicesDir.resolve("org.codehaus.groovy.transform.ASTTransformation")
        Files.writeString(
            serviceFile,
            """
            # Fake Spock-like transform
            org.spockframework.compiler.SpockTransform
            """.trimIndent(),
        )

        // Code that extends a superclass (like Spock's Specification)
        val code = """
            class MySpecTest extends spock.lang.Specification {
                def "should work"() {
                    expect:
                    true
                }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///MySpecTest.groovy"),
                content = code,
                classpath = listOf(classpathDir),
            ),
        )

        // The parser should succeed without NoClassDefFoundError
        // Note: the class may have diagnostics about unresolved superclass, but the parser shouldn't crash
        assertNotNull(result.ast, "AST should not be null even with unresolved superclass")
    }

    @Test
    fun `parse succeeds with multiple JARs containing different AST transformation service files`() {
        // Test that multiple JARs with transformation services don't cause issues

        val jar1Path = tempDir.resolve("transform1.jar")
        JarOutputStream(Files.newOutputStream(jar1Path)).use { jos ->
            val entry = JarEntry("META-INF/services/org.codehaus.groovy.transform.ASTTransformation")
            jos.putNextEntry(entry)
            jos.write("com.example.Transform1\ncom.example.Transform2\n".toByteArray())
            jos.closeEntry()
        }

        val jar2Path = tempDir.resolve("transform2.jar")
        JarOutputStream(Files.newOutputStream(jar2Path)).use { jos ->
            val entry = JarEntry("META-INF/services/org.codehaus.groovy.transform.ASTTransformation")
            jos.putNextEntry(entry)
            jos.write("org.another.Transform3\n".toByteArray())
            jos.closeEntry()
        }

        val code = """
            class MultiJarTest {
                String name = "test"
                def method() { println name }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///MultiJarTest.groovy"),
                content = code,
                classpath = listOf(jar1Path, jar2Path),
            ),
        )

        assertTrue(result.isSuccessful, "Parse should succeed with multiple JARs containing transformation services")
        assertNotNull(result.ast, "AST should be populated")
        assertEquals(0, result.diagnostics.size, "No diagnostics expected")
    }

    @Test
    fun `parse maintains AST structure when transforms are filtered`() {
        // Verify that filtering AST transformations doesn't break the AST structure
        val classpathDir = tempDir.resolve("struct-test-classpath")
        Files.createDirectories(classpathDir)

        val servicesDir = classpathDir.resolve("META-INF/services")
        Files.createDirectories(servicesDir)

        val serviceFile = servicesDir.resolve("org.codehaus.groovy.transform.ASTTransformation")
        Files.writeString(serviceFile, "com.fake.Transform\n")

        val code = """
            class Person {
                String name
                int age
                
                def greet(String greeting) {
                    return greeting + ", " + name
                }
                
                static void main(String[] args) {
                    def p = new Person(name: "Alice", age: 30)
                    println p.greet("Hello")
                }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Person.groovy"),
                content = code,
                classpath = listOf(classpathDir),
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)

        // Verify AST structure
        val classes = result.ast!!.classes
        assertTrue(classes.any { it.nameWithoutPackage == "Person" }, "Should find Person class")

        val personClass = classes.first { it.nameWithoutPackage == "Person" }
        val fields = personClass.fields.map { it.name }
        assertTrue(fields.contains("name"), "Should find 'name' field")
        assertTrue(fields.contains("age"), "Should find 'age' field")

        val methods = personClass.methods.map { it.name }
        assertTrue(methods.contains("greet"), "Should find 'greet' method")
        assertTrue(methods.contains("main"), "Should find 'main' method")
    }
}

