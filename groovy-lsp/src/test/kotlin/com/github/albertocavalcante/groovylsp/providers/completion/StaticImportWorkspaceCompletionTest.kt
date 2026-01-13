package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class StaticImportWorkspaceCompletionTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var semanticResolver: SemanticTypeResolver

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        semanticResolver = SemanticTypeResolver(ReflectionTypeSolver())
        compilationService.workspaceManager.initializeWorkspace(tempDir)
    }

    @Test
    fun `static import completion includes workspace members`() = runBlocking {
        val utilFile = tempDir / "MathUtil.groovy"
        utilFile.writeText(
            """
            package com.example

            class MathUtil {
                static int add(int a, int b) { a + b }
                static final String PI = "3.14"
            }
            """.trimIndent(),
        )

        val mainFile = tempDir / "Main.groovy"
        val lineContent = "import static com.example.MathUtil."
        val mainContent = """
            $lineContent

            class Sample {}
        """.trimIndent()
        mainFile.writeText(mainContent)

        compilationService.compile(utilFile.toUri(), utilFile.toFile().readText())
        compilationService.compile(mainFile.toUri(), mainContent)

        val completions = CompletionProvider.getContextualCompletions(
            mainFile.toUri().toString(),
            0,
            lineContent.length,
            compilationService,
            semanticResolver,
            mainContent,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("add"), "Expected static method from workspace")
        assertTrue(labels.contains("PI"), "Expected static field from workspace")
    }
}
