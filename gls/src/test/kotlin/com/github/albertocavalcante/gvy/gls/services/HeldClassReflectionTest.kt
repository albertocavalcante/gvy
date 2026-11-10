package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.reflectedInstanceMethods
import com.github.albertocavalcante.gvy.gls.providers.inlayhints.InlayHintsCandidates
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A request may hold a `Class` from [ClasspathService.loadClass] while two classpath updates close the loader
 * that defined it. Reflection that must load a not-yet-loaded dependency type then fails with a linkage error;
 * completion and inlay hints must degrade to an empty result instead of failing the request (#1292).
 */
class HeldClassReflectionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inlay constructor hints on a class whose loader was closed are empty, not an error`() {
        val held = heldClassAcrossTwoUpdates()

        val signatures = InlayHintsCandidates.classpathConstructorSignatures(held, argCount = 1)

        assertEquals(emptyList(), signatures)
    }

    @Test
    fun `completion methods on a class whose loader was closed are empty, not an error`() {
        val held = heldClassAcrossTwoUpdates()

        assertTrue(reflectedInstanceMethods(held).isEmpty())
    }

    /** `com.example.Holder` (constructor and method take `com.example.Dep`), loaded but `Dep` never touched. */
    private fun heldClassAcrossTwoUpdates(): Class<*> {
        val service = ClasspathService()
        service.updateClasspath(listOf(jar()))
        val held = checkNotNull(service.loadClass("com.example.Holder"))
        service.updateClasspath(emptyList())
        service.updateClasspath(emptyList()) // the loader that defined Holder is now closed
        return held
    }

    private fun jar(): Path {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue(compiler != null, "needs a JDK compiler")
        val sources = Files.createDirectories(tempDir.resolve("src/com/example"))
        val classes = Files.createDirectories(tempDir.resolve("classes"))
        val dep = Files.writeString(sources.resolve("Dep.java"), "package com.example; public class Dep {}")
        val holder = Files.writeString(
            sources.resolve("Holder.java"),
            "package com.example; public class Holder { public Holder(Dep d) {} public Dep use(Dep d) { return d; } }",
        )
        check(compiler.run(null, null, null, "-d", classes.toString(), dep.toString(), holder.toString()) == 0)
        val jar = tempDir.resolve("holder.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            listOf("Dep", "Holder").forEach { name ->
                out.putNextEntry(JarEntry("com/example/$name.class"))
                Files.copy(classes.resolve("com/example/$name.class"), out)
                out.closeEntry()
            }
        }
        return jar
    }
}
