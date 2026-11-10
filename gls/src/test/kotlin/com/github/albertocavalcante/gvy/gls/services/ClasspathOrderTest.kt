package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals

/** The JVM resolves from the first jar that has a class: reordering jars must reach the classpath loader. */
class ClasspathOrderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reordering jars that define the same class resolves it from the new first jar`() {
        val first = jarWithShared("first")
        val second = jarWithShared("second")
        val compilation = GroovyCompilationService()
        compilation.updateWorkspaceModel(tempDir, listOf(first, second), emptyList())
        assertEquals("first", origin(compilation.classpathService))

        compilation.updateWorkspaceModel(tempDir, listOf(second, first), emptyList())

        assertEquals("second", origin(compilation.classpathService), "the loader kept the old order")
    }

    /** Which jar the class the service loads came from. */
    private fun origin(service: ClasspathService): String {
        val shared = checkNotNull(service.loadClass(SHARED)) { "$SHARED did not load" }
        return shared.getField("ORIGIN").get(null) as String
    }

    /** A jar with `com.example.Shared`, whose `ORIGIN` constant names the jar. */
    private fun jarWithShared(origin: String): Path {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue(compiler != null, "needs a JDK compiler")
        val sources = Files.createDirectories(tempDir.resolve("$origin-src/com/example"))
        val classes = Files.createDirectories(tempDir.resolve("$origin-classes"))
        val source = Files.writeString(
            sources.resolve("Shared.java"),
            "package com.example; public class Shared { public static final String ORIGIN = \"$origin\"; }",
        )
        check(compiler.run(null, null, null, "-d", classes.toString(), source.toString()) == 0) { "javac failed" }
        val jar = tempDir.resolve("$origin.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("com/example/Shared.class"))
            Files.copy(classes.resolve("com/example/Shared.class"), out)
            out.closeEntry()
        }
        return jar
    }

    private companion object {
        const val SHARED = "com.example.Shared"
    }
}
