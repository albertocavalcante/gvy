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
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeGroovySdkTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `default isolated compiler resolves and expands bundled annotations without a supplied project SDK`() {
        val source =
            "import groovy.transform.Immutable\n" +
                "import groovy.transform.CompileStatic\n" +
                "@Immutable\n@CompileStatic\nclass Money { BigDecimal amount }"
        val parsed = GroovyParserFacade().parse(
            ParseRequest(URI.create("file:///Money.groovy"), source, compilePhase = Phases.CANONICALIZATION),
        )
        parsed.compilationUnit.classLoader.use { loader ->
            assertFalse(parsed.outcome.isDegraded, "Actual bundled annotations must resolve without synthetic stubs")
            assertNotNull(parsed.classpathInputs?.compilerSdk)
            assertTrue(parsed.sourceAnnotations.references.any { it.binaryName == "groovy.transform.Immutable" })
            val money = assertNotNull(parsed.ast).classes.single { it.name == "Money" }
            assertTrue(
                money.annotations.none { it.classNode.name == "groovy.transform.Immutable" },
                "Collector expansion must actually happen",
            )
            assertNotNull(loader.getResource("groovy/transform/CompileStatic.class"))
        }
    }

    @Test
    fun `fallback exposes exact trusted core classes and resources while server internals remain hidden`() {
        SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.selectSdkFallback()
            assertNotNull(loader.compilerSdk)
            assertSame(groovy.transform.CompileStatic::class.java, loader.loadClass("groovy.transform.CompileStatic"))
            assertNotNull(loader.getResource("groovy/transform/CompileStatic.class"))
            assertFailsWith<ClassNotFoundException> { loader.loadClass(GroovyParserFacade::class.java.name) }
            assertNull(loader.getResource("com/github/albertocavalcante/groovyparser/GroovyParserFacade.class"))
            assertNull(loader.getResource("META-INF/gvy/sources/groovy.properties"))
            assertNull(loader.getResource(AST_TRANSFORMATION_SERVICE_FILE))
            assertFalse(loader.getResources(AST_TRANSFORMATION_SERVICE_FILE).hasMoreElements())
        }
    }

    @Test
    fun `a project SDK marker disables every fallback including unavailable project annotations`() {
        val sdk = classJar("partial-sdk.jar", "groovy/lang/GroovyObject.class")
        SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.addClasspath(sdk.toString())
            loader.selectSdkFallback()
            assertNull(loader.compilerSdk)
            assertTrue(
                assertNotNull(loader.getResource("groovy/lang/GroovyObject.class"))
                    .toString().contains("partial-sdk.jar"),
            )
            assertNotSame(groovy.lang.GroovyObject::class.java, loader.loadClass("groovy.lang.GroovyObject"))
            assertFailsWith<ClassNotFoundException> { loader.loadClass("groovy.transform.CompileStatic") }
            assertNull(loader.getResource("groovy/transform/CompileStatic.class"))
        }
    }

    @Test
    fun `project classes take precedence even while the rest of the core SDK uses fallback`() {
        val override = classJar("project.jar", "groovy/transform/CompileStatic.class")
        SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.addClasspath(override.toString())
            loader.selectSdkFallback()
            assertNotNull(loader.compilerSdk)
            val selected = loader.loadClass("groovy.transform.CompileStatic")
            assertNotSame(groovy.transform.CompileStatic::class.java, selected)
            assertEquals(override.toUri(), selected.protectionDomain.codeSource.location.toURI())
            assertTrue(
                assertNotNull(loader.getResource("groovy/transform/CompileStatic.class"))
                    .toString().contains("project.jar"),
            )
        }
    }

    @Test
    fun `a source-only project SDK disables fallback rather than mixing in process SDK annotations`() {
        for (extension in listOf("groovy", "java")) {
            val root = directory.resolve(extension)
            val source = root.resolve("groovy/lang/GroovyObject.$extension")
            Files.createDirectories(source.parent)
            Files.writeString(source, "package groovy.lang; public interface GroovyObject {}")
            SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
                loader.addClasspath(root.toString())
                loader.selectSdkFallback()
                assertNull(loader.compilerSdk)
                assertNull(loader.getResource("groovy/transform/CompileStatic.class"))
                assertFailsWith<ClassNotFoundException> { loader.loadClass("groovy.transform.CompileStatic") }
            }
        }
    }

    private fun classJar(name: String, entry: String): Path {
        val path = directory.resolve(name)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry(entry))
            val input = assertNotNull(groovy.lang.GroovyObject::class.java.classLoader.getResourceAsStream(entry))
            input.use { it.copyTo(jar) }
            jar.closeEntry()
        }
        return path
    }
}
