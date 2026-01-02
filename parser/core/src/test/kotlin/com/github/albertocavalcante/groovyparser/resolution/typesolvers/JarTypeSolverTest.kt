package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarTypeSolverTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Creates a minimal JAR file with dummy class entries for testing.
     * Note: These are just entry names, not actual compiled classes.
     * For actual class loading tests, we'd use real JARs on the classpath.
     */
    private fun createDummyJar(jarName: String, classNames: List<String>): Path {
        val jarPath = tempDir.resolve(jarName)
        JarOutputStream(FileOutputStream(jarPath.toFile())).use { jos ->
            classNames.forEach { className ->
                val entryName = className.replace('.', '/') + ".class"
                jos.putNextEntry(JarEntry(entryName))
                // Write minimal class bytes (just enough to make it look like a class)
                jos.write(byteArrayOf())
                jos.closeEntry()
            }
        }
        return jarPath
    }

    @Test
    fun `throws exception for non-existent JAR`() {
        val nonExistentPath = tempDir.resolve("nonexistent.jar")
        assertFailsWith<IllegalArgumentException> {
            JarTypeSolver(nonExistentPath)
        }
    }

    @Test
    fun `throws exception for non-JAR file`() {
        val txtFile = tempDir.resolve("test.txt")
        txtFile.toFile().writeText("not a jar")
        assertFailsWith<IllegalArgumentException> {
            JarTypeSolver(txtFile)
        }
    }

    @Test
    fun `indexes classes from JAR`() {
        val jarPath = createDummyJar(
            "test.jar",
            listOf("com.example.MyClass", "com.example.AnotherClass"),
        )

        JarTypeSolver(jarPath).use { solver ->
            val knownClasses = solver.getKnownClasses()
            assertEquals(2, knownClasses.size)
            assertTrue(knownClasses.contains("com.example.MyClass"))
            assertTrue(knownClasses.contains("com.example.AnotherClass"))
        }
    }

    @Test
    fun `getClassCount returns correct count`() {
        val jarPath = createDummyJar(
            "test.jar",
            listOf("com.example.One", "com.example.Two", "com.example.Three"),
        )

        JarTypeSolver(jarPath).use { solver ->
            assertEquals(3, solver.getClassCount())
        }
    }

    @Test
    fun `excludes inner classes from index`() {
        val jarPath = createDummyJar(
            "test.jar",
            listOf("com.example.Outer", "com.example.Outer\$Inner"),
        )

        JarTypeSolver(jarPath).use { solver ->
            val knownClasses = solver.getKnownClasses()
            // Should only have the outer class, not inner class
            assertEquals(1, knownClasses.size)
            assertTrue(knownClasses.contains("com.example.Outer"))
            assertFalse(knownClasses.contains("com.example.Outer\$Inner"))
        }
    }

    @Test
    fun `returns unsolved for class not in JAR`() {
        val jarPath = createDummyJar("test.jar", listOf("com.example.MyClass"))

        JarTypeSolver(jarPath).use { solver ->
            val ref = solver.tryToSolveType("com.notinjar.SomeClass")
            assertFalse(ref.isSolved)
        }
    }

    @Test
    fun `toString includes path and class count`() {
        val jarPath = createDummyJar(
            "mylib.jar",
            listOf("com.example.One", "com.example.Two"),
        )

        JarTypeSolver(jarPath).use { solver ->
            val str = solver.toString()
            assertTrue(str.contains("mylib.jar"))
            assertTrue(str.contains("2 classes"))
        }
    }

    @Test
    fun `fromJars creates combined solver`() {
        val jar1 = createDummyJar("lib1.jar", listOf("com.lib1.Class1"))
        val jar2 = createDummyJar("lib2.jar", listOf("com.lib2.Class2"))

        val combined = JarTypeSolver.fromJars(jar1, jar2)
        // Combined solver should have both JarTypeSolvers as children
        assertTrue(combined.toString().contains("CombinedTypeSolver"))
    }

    @Test
    fun `fromDirectory creates solver from all JARs`() {
        createDummyJar("lib1.jar", listOf("com.lib1.Class1"))
        createDummyJar("lib2.jar", listOf("com.lib2.Class2"))
        // Create a non-JAR file that should be ignored
        tempDir.resolve("readme.txt").toFile().writeText("not a jar")

        val combined = JarTypeSolver.fromDirectory(tempDir)
        assertTrue(combined.toString().contains("CombinedTypeSolver"))
    }

    @Test
    fun `can be used with CombinedTypeSolver`() {
        val jarPath = createDummyJar("test.jar", listOf("com.example.MyClass"))

        val combined = CombinedTypeSolver()
        combined.add(ReflectionTypeSolver())
        combined.add(JarTypeSolver(jarPath))

        // Should resolve JRE types
        assertTrue(combined.hasType("java.lang.String"))
    }

    @Test
    fun `resolves real class from actual JAR on classpath`() {
        // groovy-core JAR should be on the classpath
        // We can find it by looking at where Groovy classes are loaded from
        val groovyClass = groovy.lang.GroovyObject::class.java
        val location = groovyClass.protectionDomain?.codeSource?.location

        if (location != null && location.path.endsWith(".jar")) {
            val jarPath = Path.of(location.toURI())
            JarTypeSolver(jarPath).use { solver ->
                val ref = solver.tryToSolveType("groovy.lang.Closure")
                assertTrue(ref.isSolved, "Should resolve groovy.lang.Closure from Groovy JAR")
                assertEquals("groovy.lang.Closure", ref.getDeclaration().qualifiedName)
            }
        }
        // If Groovy is not loaded from a JAR (e.g., exploded classes), skip this test
    }
}
