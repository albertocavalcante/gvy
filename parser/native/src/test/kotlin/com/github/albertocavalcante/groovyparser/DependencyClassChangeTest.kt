// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A dependency rebuilt at the same path must be seen by the next compile in the same process. The compiler keeps
 * what it read from a class file under the file's URL and never drops it, so without a URL that follows the file's
 * version a rebuilt class kept its old members until the server restarted.
 */
class DependencyClassChangeTest {
    @TempDir
    lateinit var tempDir: Path

    private fun groovyJar(): Path =
        Path.of(Class.forName("groovy.lang.Closure").protectionDomain.codeSource.location.toURI())

    /** Compiles `dep.Thing` with one public method named [method] and returns its class file. */
    private fun thingWith(method: String): Path {
        val out = Files.createTempDirectory(tempDir, "thing")
        val source = out.resolve("Thing.java")
        source.writeText("package dep; public class Thing { public String $method() { return \"\"; } }")
        val javac = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "this test needs a JDK, not a JRE" }
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), source.toString()), "javac failed")
        return out.resolve("dep").resolve("Thing.class")
    }

    /** The methods `@Delegate` copies from `dep.Thing`, as a fresh facade sees them on [classpathEntry]. */
    private fun delegatedMethods(classpathEntry: Path): Set<String> {
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = URI.create("file:///A.groovy"),
                content = "class A { @Delegate dep.Thing thing }",
                classpath = listOf(groovyJar(), classpathEntry),
                compilePhase = Phases.CANONICALIZATION,
            ),
        )
        // Released like a published result is, so the jar is not held open while the test rewrites it.
        return result.compilationUnit.classLoader.use {
            val methods = checkNotNull(result.ast).classes.single().methods
            methods.map { it.name }.filterTo(HashSet()) { it in THING_METHODS }
        }
    }

    private fun writeJar(jar: Path, classFile: Path) {
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("dep/Thing.class"))
            out.write(Files.readAllBytes(classFile))
            out.closeEntry()
        }
    }

    /** A new class directory named [name] that already has the `dep` package directory. */
    private fun classDirectory(name: String): Path {
        val classes = tempDir.resolve(name)
        Files.createDirectories(classes.resolve("dep"))
        return classes
    }

    @Test
    fun `a class file replaced in a class directory is read again`() {
        val classes = classDirectory("classes")
        val target = classes.resolve("dep").resolve("Thing.class")

        Files.copy(thingWith("alpha"), target)
        val before = delegatedMethods(classes)
        Files.copy(thingWith("beta"), target, StandardCopyOption.REPLACE_EXISTING)
        val after = delegatedMethods(classes)

        assertEquals(setOf("alpha"), before)
        assertEquals(setOf("beta"), after)
    }

    @Test
    fun `a class inside a jar rebuilt at the same path is read again`() {
        val jar = tempDir.resolve("dependency.jar")

        writeJar(jar, thingWith("alpha"))
        val before = delegatedMethods(jar)
        writeJar(jar, thingWith("beta"))
        val after = delegatedMethods(jar)

        assertEquals(setOf("alpha"), before)
        assertEquals(setOf("beta"), after)
    }

    @Test
    fun `a rebuild of the same size is told apart by its modification time`() {
        val classes = classDirectory("classes")
        val target = classes.resolve("dep").resolve("Thing.class")
        val alpha = thingWith("alpha")
        val gamma = thingWith("gamma")
        assertEquals(Files.size(alpha), Files.size(gamma), "the two builds must be the same size for this test")

        Files.copy(alpha, target)
        val built = Files.getLastModifiedTime(target)
        val before = delegatedMethods(classes)
        Files.copy(gamma, target, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(target, FileTime.fromMillis(built.toMillis() + REBUILD_DELAY_MILLIS))
        val after = delegatedMethods(classes)

        assertEquals(setOf("alpha"), before)
        assertEquals(setOf("gamma"), after)
    }

    @Test
    fun `a class directory and a jar with a space in their path are read again too`() {
        val classes = classDirectory("my classes")
        val target = classes.resolve("dep").resolve("Thing.class")
        val jar = Files.createDirectories(tempDir.resolve("my libs")).resolve("my dependency.jar")

        Files.copy(thingWith("alpha"), target)
        writeJar(jar, thingWith("alpha"))
        val before = delegatedMethods(classes) to delegatedMethods(jar)
        Files.copy(thingWith("beta"), target, StandardCopyOption.REPLACE_EXISTING)
        writeJar(jar, thingWith("beta"))
        val after = delegatedMethods(classes) to delegatedMethods(jar)

        assertEquals(setOf("alpha") to setOf("alpha"), before)
        assertEquals(setOf("beta") to setOf("beta"), after)
    }

    @Test
    fun `an unchanged class file keeps its URL so it stays remembered`() {
        val target = classDirectory("classes").resolve("dep").resolve("Thing.class")
        Files.copy(thingWith("alpha"), target)
        val resource = target.toUri().toURL()

        val first = ClassFileVersions().versioned(resource)
        val second = ClassFileVersions().versioned(resource)
        Files.copy(thingWith("beta"), target, StandardCopyOption.REPLACE_EXISTING)
        val changed = ClassFileVersions().versioned(resource)

        assertEquals(first.toURI(), second.toURI())
        assertNotEquals(first.toURI(), changed.toURI())
        assertEquals(Files.readAllBytes(target).toList(), changed.openStream().use { it.readAllBytes() }.toList())
    }

    @Test
    fun `an unchanged class inside a jar keeps its URL too`() {
        val jar = tempDir.resolve("dependency.jar")
        writeJar(jar, thingWith("alpha"))
        val resource = URI.create("jar:${jar.toUri()}!/dep/Thing.class").toURL()

        val first = ClassFileVersions().versioned(resource)
        val second = ClassFileVersions().versioned(resource)

        assertNotEquals(resource.toURI(), first.toURI())
        assertEquals(first.toURI(), second.toURI())
    }

    @Test
    fun `a resource that is not a local file or jar is left as it is`() {
        val jdkClass = checkNotNull(Any::class.java.getResource("Object.class"))
        val remoteJar = URI.create("jar:http://example.invalid/dependency.jar!/dep/Thing.class").toURL()

        assertEquals(jdkClass, ClassFileVersions().versioned(jdkClass))
        assertEquals(remoteJar.toURI(), ClassFileVersions().versioned(remoteJar).toURI())
    }

    @Test
    fun `the compile class loader versions class files and nothing else`() {
        val classes = classDirectory("classes")
        Files.copy(thingWith("alpha"), classes.resolve("dep").resolve("Thing.class"))
        val note = classes.resolve("dep").resolve("note.txt").also { it.writeText("not a class") }

        SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.addClasspath(classes.toString())

            assertEquals(note.toUri(), checkNotNull(loader.getResource("dep/note.txt")).toURI())
            assertNotEquals("file", checkNotNull(loader.getResource("dep/Thing.class")).protocol)
        }
    }

    private companion object {
        val THING_METHODS = setOf("alpha", "beta", "gamma")
        const val REBUILD_DELAY_MILLIS = 5_000L
    }
}
