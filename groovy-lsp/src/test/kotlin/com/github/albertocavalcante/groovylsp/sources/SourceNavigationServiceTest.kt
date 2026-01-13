package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.buildtool.SourceArtifactResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for SourceNavigationService.
 */
class SourceNavigationServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class StatisticsTest {

        @Test
        fun `getStatistics returns expected keys`() {
            val service = SourceNavigationService()

            val stats = service.getStatistics()

            assertTrue(stats.containsKey("extractorStats"), "Should contain extractorStats key")
        }

        @Test
        fun `getStatistics returns correct types`() {
            val service = SourceNavigationService()

            val stats = service.getStatistics()

            assertTrue(stats["extractorStats"] is Map<*, *>, "extractorStats should be Map")
        }

        @Test
        fun `getStatistics starts with zero extracted jars`() {
            val service = SourceNavigationService()

            val stats = service.getStatistics()
            val extractorStats = stats["extractorStats"] as Map<*, *>

            assertTrue((extractorStats["extractedJars"] as Int) == 0, "Should start with 0 extracted jars")
        }
    }

    @Nested
    inner class SourceResultTest {

        @Test
        fun `SourceResult SourceLocation holds all data`() {
            val uri = URI.create("file:///test/Foo.java")
            val result = SourceNavigator.SourceResult.SourceLocation(
                uri = uri,
                className = "com.example.Foo",
                lineNumber = 42,
            )

            assertTrue(result.uri == uri)
            assertTrue(result.className == "com.example.Foo")
            assertTrue(result.lineNumber == 42)
        }

        @Test
        fun `SourceResult SourceLocation with default lineNumber`() {
            val uri = URI.create("file:///test/Bar.java")
            val result = SourceNavigator.SourceResult.SourceLocation(
                uri = uri,
                className = "com.example.Bar",
            )

            assertEquals(null, result.lineNumber, "Default lineNumber should be null")
        }

        @Test
        fun `SourceResult BinaryOnly includes reason`() {
            val uri = URI.create("jar:file:///test.jar!/Foo.class")
            val result = SourceNavigator.SourceResult.BinaryOnly(
                uri = uri,
                className = "com.example.Foo",
                reason = "No source available",
            )

            assertTrue(result.reason.isNotBlank())
            assertEquals("com.example.Foo", result.className)
        }
    }

    @Nested
    inner class NavigateToSourceTest {

        @Test
        fun `returns BinaryOnly for invalid jar URI scheme`() = runBlocking {
            val service = SourceNavigationService()

            // file: scheme is not a jar: URI
            val fileUri = URI.create("file:///some/path/Class.java")
            val result = service.navigateToSource(fileUri, "SomeClass")

            assertTrue(result is SourceNavigator.SourceResult.BinaryOnly)
            val binaryResult = result as SourceNavigator.SourceResult.BinaryOnly
            assertTrue(binaryResult.reason.contains("extract JAR path") || binaryResult.reason.contains("JDK"))
        }

        @Test
        fun `handles jrt URI for JDK classes`() = runBlocking {
            val service = SourceNavigationService()

            val jrtUri = URI.create("jrt:/java.base/java/util/Date.class")
            val result = service.navigateToSource(jrtUri, "java.util.Date")

            // Should delegate to JdkSourceResolver - result depends on src.zip availability
            assertNotNull(result)
            assertTrue(
                result is SourceNavigator.SourceResult.SourceLocation ||
                    result is SourceNavigator.SourceResult.BinaryOnly,
            )
        }

        @Test
        fun `returns BinaryOnly when jar path does not exist`() = runBlocking {
            val service = SourceNavigationService()

            val nonExistentJar = URI.create("jar:file:///nonexistent/lib.jar!/com/example/Foo.class")
            val result = service.navigateToSource(nonExistentJar, "com.example.Foo")

            assertTrue(result is SourceNavigator.SourceResult.BinaryOnly)
        }

        @Test
        fun `finds adjacent source JAR for local dependencies`() = runBlocking {
            // Create binary JAR and matching source JAR
            val libDir = tempDir.resolve("libs")
            Files.createDirectories(libDir)

            val binaryJar = libDir.resolve("mylib.jar")
            val sourceJar = libDir.resolve("mylib-sources.jar")

            createMinimalJar(binaryJar)
            createSourceJar(sourceJar, "com/example/MyClass.java", "package com.example; public class MyClass {}")

            val extractionDir = tempDir.resolve("extracted")
            val extractor = SourceJarExtractor(extractionDir)
            val service = SourceNavigationService(
                sourceExtractor = extractor,
            )

            val jarUri = URI.create("jar:file://${binaryJar.toAbsolutePath()}!/com/example/MyClass.class")
            val result = service.navigateToSource(jarUri, "com.example.MyClass")

            assertTrue(result is SourceNavigator.SourceResult.SourceLocation) {
                "Expected SourceLocation but got: $result"
            }
        }

        @Test
        fun `returns SourceLocation for groovy sources`() = runBlocking {
            val libDir = tempDir.resolve("libs")
            Files.createDirectories(libDir)

            val binaryJar = libDir.resolve("mylib.jar")
            val sourceJar = libDir.resolve("mylib-sources.jar")

            createMinimalJar(binaryJar)

            val groovySource = """
                package com.example

                class MyGroovy {
                }
            """.trimIndent()
            createSourceJar(sourceJar, "com/example/MyGroovy.groovy", groovySource)

            val extractionDir = tempDir.resolve("extracted")
            val extractor = SourceJarExtractor(extractionDir)
            val service = SourceNavigationService(sourceExtractor = extractor)

            val jarUri = URI.create("jar:file://${binaryJar.toAbsolutePath()}!/com/example/MyGroovy.class")
            val result = service.navigateToSource(jarUri, "com.example.MyGroovy")

            assertTrue(result is SourceNavigator.SourceResult.SourceLocation) {
                "Expected SourceLocation but got: $result"
            }
            val location = result as SourceNavigator.SourceResult.SourceLocation
            assertTrue(location.uri.toString().endsWith("MyGroovy.groovy"))
            assertEquals(3, location.lineNumber)
        }

        @Test
        fun `derives maven coordinates from gradle cache paths`() = runBlocking {
            val resolver = RecordingSourceArtifactResolver()
            val service = SourceNavigationService(sourceResolver = resolver)

            val jarPath = Paths.get(
                tempDir.toString(),
                ".gradle",
                "caches",
                "modules-2",
                "files-2.1",
                "com.lesfurets.jenkins.unit",
                "jenkins-pipeline-unit",
                "1.9",
                "abcdef",
                "jenkins-pipeline-unit-1.9.jar",
            )
            val jarUri = URI.create(
                "jar:file://${jarPath.toAbsolutePath()}!/com/lesfurets/jenkins/unit/DeclarativePipelineTest.class",
            )

            service.navigateToSource(jarUri, "com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest")

            assertEquals("com.lesfurets.jenkins.unit", resolver.groupId)
            assertEquals("jenkins-pipeline-unit", resolver.artifactId)
            assertEquals("1.9", resolver.version)
        }
    }

    @Nested
    inner class NavigateToMethodSourceTest {

        @Test
        fun `navigates to method line number in Java source`() = runBlocking {
            // Create a JAR with source code
            val libDir = tempDir.resolve("libs")
            Files.createDirectories(libDir)

            val binaryJar = libDir.resolve("mylib.jar")
            val sourceJar = libDir.resolve("mylib-sources.jar")

            createMinimalJar(binaryJar)

            // Create Java source with a method at a specific line
            val javaSource = """
                package com.example;

                public class MyClass {
                    public void doSomething() {
                        // Method body
                    }

                    public int calculate(int x) {
                        return x * 2;
                    }
                }
            """.trimIndent()
            createSourceJar(sourceJar, "com/example/MyClass.java", javaSource)

            val extractionDir = tempDir.resolve("extracted")
            val extractor = SourceJarExtractor(extractionDir)
            val service = SourceNavigationService(sourceExtractor = extractor)

            val jarUri = URI.create("jar:file://${binaryJar.toAbsolutePath()}!/com/example/MyClass.class")
            val result = service.navigateToMethodSource(jarUri, "com.example.MyClass", "doSomething")

            assertTrue(result is SourceNavigator.SourceResult.SourceLocation) {
                "Expected SourceLocation but got: $result"
            }
            val location = result as SourceNavigator.SourceResult.SourceLocation
            val lineNumber = assertNotNull(location.lineNumber, "Should have line number for method")
            assertTrue(lineNumber > 3, "Method should be after class declaration")
        }

        @Test
        fun `falls back to class-level navigation when method not found`() = runBlocking {
            val libDir = tempDir.resolve("libs")
            Files.createDirectories(libDir)

            val binaryJar = libDir.resolve("mylib.jar")
            val sourceJar = libDir.resolve("mylib-sources.jar")

            createMinimalJar(binaryJar)

            val javaSource = """
                package com.example;

                public class MyClass {
                    public void existingMethod() {
                    }
                }
            """.trimIndent()
            createSourceJar(sourceJar, "com/example/MyClass.java", javaSource)

            val extractionDir = tempDir.resolve("extracted")
            val extractor = SourceJarExtractor(extractionDir)
            val service = SourceNavigationService(sourceExtractor = extractor)

            val jarUri = URI.create("jar:file://${binaryJar.toAbsolutePath()}!/com/example/MyClass.class")
            // Try to navigate to a method that doesn't exist
            val result = service.navigateToMethodSource(jarUri, "com.example.MyClass", "nonExistentMethod")

            assertTrue(result is SourceNavigator.SourceResult.SourceLocation) {
                "Should fall back to class-level navigation"
            }
            val location = result as SourceNavigator.SourceResult.SourceLocation
            // Should return class-level location (no method-specific line)
            assertEquals("com.example.MyClass", location.className, "Should preserve class name on fallback")
        }

        @Test
        fun `returns BinaryOnly when source not available for method`() = runBlocking {
            val service = SourceNavigationService()

            // Use a non-existent JAR
            val jarUri = URI.create("jar:file:///nonexistent/lib.jar!/com/example/MyClass.class")
            val result = service.navigateToMethodSource(jarUri, "com.example.MyClass", "someMethod")

            assertTrue(result is SourceNavigator.SourceResult.BinaryOnly) {
                "Expected BinaryOnly when source not available"
            }
        }

        @Test
        fun `handles method overloads by name only`() = runBlocking {
            // Note: Current implementation matches by method name only (not signature)
            val libDir = tempDir.resolve("libs")
            Files.createDirectories(libDir)

            val binaryJar = libDir.resolve("mylib.jar")
            val sourceJar = libDir.resolve("mylib-sources.jar")

            createMinimalJar(binaryJar)

            val javaSource = """
                package com.example;

                public class MyClass {
                    public void add(int x) {
                    }

                    public void add(int x, int y) {
                    }
                }
            """.trimIndent()
            createSourceJar(sourceJar, "com/example/MyClass.java", javaSource)

            val extractionDir = tempDir.resolve("extracted")
            val extractor = SourceJarExtractor(extractionDir)
            val service = SourceNavigationService(sourceExtractor = extractor)

            val jarUri = URI.create("jar:file://${binaryJar.toAbsolutePath()}!/com/example/MyClass.class")
            val result = service.navigateToMethodSource(jarUri, "com.example.MyClass", "add")

            // With overloads, JavaSourceInspector returns null (ambiguous)
            // So we should fall back to class-level navigation
            assertTrue(result is SourceNavigator.SourceResult.SourceLocation)
            val location = result as SourceNavigator.SourceResult.SourceLocation
            assertEquals("com.example.MyClass", location.className)
        }

        @Test
        fun `works with JDK classes that have source available`() = runBlocking {
            val service = SourceNavigationService()

            // Test with a JDK class - result depends on src.zip availability
            val jrtUri = URI.create("jrt:/java.base/java/util/ArrayList.class")
            val result = service.navigateToMethodSource(jrtUri, "java.util.ArrayList", "add")

            assertNotNull(result, "Should return a result for JDK method")
            // Result type depends on whether src.zip is present
            assertTrue(
                result is SourceNavigator.SourceResult.SourceLocation ||
                    result is SourceNavigator.SourceResult.BinaryOnly,
            )
        }
    }

    @Nested
    inner class InstanceTest {

        @Test
        fun `multiple instances are independent`() {
            val service1 = SourceNavigationService()
            val service2 = SourceNavigationService()

            val stats1 = service1.getStatistics()
            val stats2 = service2.getStatistics()

            // Both should work independently
            assertNotNull(stats1)
            assertNotNull(stats2)
        }
    }

    private fun createMinimalJar(path: Path) {
        java.util.jar.JarOutputStream(Files.newOutputStream(path)).use { jar ->
            val manifestEntry = java.util.jar.JarEntry("META-INF/MANIFEST.MF")
            jar.putNextEntry(manifestEntry)
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
    }

    private fun createSourceJar(path: Path, entryPath: String, content: String) {
        java.util.jar.JarOutputStream(Files.newOutputStream(path)).use { jar ->
            val entry = java.util.jar.JarEntry(entryPath)
            jar.putNextEntry(entry)
            jar.write(content.toByteArray())
            jar.closeEntry()
        }
    }

    private class RecordingSourceArtifactResolver : SourceArtifactResolver {
        var groupId: String? = null
        var artifactId: String? = null
        var version: String? = null
        override val cacheDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))

        override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? {
            this.groupId = groupId
            this.artifactId = artifactId
            this.version = version
            return null
        }

        override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
    }
}
