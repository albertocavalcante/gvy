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
        val exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), source.toString())
        assertEquals(0, exit, "javac failed")
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
        return result.ast!!.classes.single().methods.map { it.name }.filterTo(HashSet()) { it in THING_METHODS }
    }

    private fun writeJar(jar: Path, classFile: Path) {
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("dep/Thing.class"))
            out.write(Files.readAllBytes(classFile))
            out.closeEntry()
        }
    }

    @Test
    fun `a class file replaced in a class directory is read again`() {
        val classes = tempDir.resolve("classes")
        val target = Files.createDirectories(classes.resolve("dep")).resolve("Thing.class")

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
    fun `an unchanged class file keeps its URL so it stays remembered`() {
        val target = Files.createDirectories(tempDir.resolve("classes").resolve("dep")).resolve("Thing.class")
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
    fun `a resource that is not a local file or jar is left as it is`() {
        val resource = Any::class.java.getResource("Object.class")!!

        assertEquals(resource, ClassFileVersions().versioned(resource))
    }

    private companion object {
        val THING_METHODS = setOf("alpha", "beta")
    }
}
