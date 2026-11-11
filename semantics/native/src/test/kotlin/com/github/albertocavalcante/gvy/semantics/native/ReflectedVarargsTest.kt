package com.github.albertocavalcante.gvy.semantics.native

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Arrays
import kotlin.test.assertEquals

/**
 * Classpath methods show `Type...` only when the source declared it. The JVM varargs flag says so for
 * Java-compiled classes, but Groovy sets it on every trailing array parameter (#1418).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReflectedVarargsTest {
    private val classes: File = Files.createTempDirectory("groovy-compiled").toFile()
    private val loader: URLClassLoader by lazy {
        val source = """
            interface GroovyApi { void run(String[] args) }
            class GroovyTool { static void run(String[] args) {} }
            @groovy.transform.CompileStatic class StaticTool { static void run(String[] args) {} }
            trait GroovyTrait { void run(String[] args) {} }
        """.trimIndent()
        CompilationUnit(CompilerConfiguration().apply { targetDirectory = classes }).apply {
            addSource("Fixtures.groovy", source)
            compile()
        }
        URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader)
    }

    @AfterAll
    fun cleanup() {
        loader.close()
        classes.deleteRecursively()
    }

    @Test
    fun `java varargs show as varargs, on classes and interfaces`() {
        val format = String::class.java.getMethod("format", String::class.java, Array<Any>::class.java)
        assertEquals(listOf("String", "Object..."), format.displayParameterTypes())

        val of = List::class.java.getMethod("of", Array<Any>::class.java)
        assertEquals(listOf("Object..."), of.displayParameterTypes())
    }

    @Test
    fun `java explicit arrays stay arrays`() {
        val sort = Arrays::class.java.getMethod("sort", IntArray::class.java)
        assertEquals(listOf("int[]"), sort.displayParameterTypes())
    }

    @Test
    fun `groovy-compiled trailing arrays stay arrays, including interfaces and traits`() {
        listOf("GroovyApi", "GroovyTool", "StaticTool", "GroovyTrait").forEach { name ->
            val run = loader.loadClass(name).getMethod("run", Array<String>::class.java)
            check(run.isVarArgs) { "$name: Groovy is expected to set the varargs flag" }
            assertEquals(listOf("String[]"), run.displayParameterTypes(), name)
        }
    }
}
