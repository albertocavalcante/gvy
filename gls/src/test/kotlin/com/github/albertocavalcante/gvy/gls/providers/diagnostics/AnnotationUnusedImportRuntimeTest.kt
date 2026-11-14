// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import groovy.transform.CompileStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** These controls require the real Groovy runtime; stubbed annotations cannot reproduce collector expansion. */
class AnnotationUnusedImportRuntimeTest {
    private val uri = URI.create("file:///Money.groovy")

    @Test
    fun `real Immutable and CompileStatic usages preserve their imports after collector expansion`(): Unit =
        runBlocking {
            checkUnusedImports(
                """
            import groovy.transform.Immutable
            import groovy.transform.CompileStatic
            @Immutable
            @CompileStatic
            class Money { BigDecimal amount; String currency }
                """.trimIndent(),
                expectedUnused = emptyList(),
            )
        }

    @Test
    fun `the real Immutable collector does not credit an imported generated ToString annotation`(): Unit = runBlocking {
        checkUnusedImports(
            """
            import groovy.transform.Immutable
            import groovy.transform.CompileStatic
            import groovy.transform.ToString
            @Immutable
            @CompileStatic
            class Money { BigDecimal amount; String currency }
            """.trimIndent(),
            expectedUnused = listOf("groovy.transform.ToString"),
        )
    }

    private suspend fun checkUnusedImports(source: String, expectedUnused: List<String>) {
        val compilation = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        try {
            assertTrue(compilation.compile(uri, source).isSuccess, "The real runtime fixture must compile")
            val parsed = assertNotNull(compilation.getCompiledSnapshot(uri)).parseResult
            assertFalse(parsed.outcome.isDegraded, "Stubbed annotation types cannot prove this regression")
            val ast = assertNotNull(parsed.ast)
            assertTrue(
                ast.classes.single { it.name == "Money" }.annotations.none { annotation ->
                    annotation.classNode.name == "groovy.transform.Immutable"
                },
                "The original collector must actually have expanded before checking import usage",
            )

            assertEquals(expectedUnused, UnusedImportDetector.detectUnusedImports(ast).map { it.className })
        } finally {
            compilation.shutdown()
        }
    }
}
