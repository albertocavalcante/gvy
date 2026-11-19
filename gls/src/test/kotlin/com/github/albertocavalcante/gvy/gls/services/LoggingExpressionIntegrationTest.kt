// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.ajalt.clikt.core.parse
import com.github.albertocavalcante.gvy.fmt.TokenStreamGuard
import com.github.albertocavalcante.gvy.gls.cli.GlsCommand
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.codeaction.FormattingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

@Isolated("Captures CLI output")
class LoggingExpressionIntegrationTest {
    private val source = """
        import groovy.util.logging.Slf4j
        @Slf4j
        public class LintBait {
        String debug(String a, String b, String c, String d, String e) {
        log.debug("debug: ${'$'}{a} ${'$'}{b} ${'$'}{c} ${'$'}{d} ${'$'}{e}")
        a
        }
        }
    """.trimIndent()

    @Test
    fun `document formatting preserves the annotated logging expression`() {
        val uri = URI.create("file:///LintBait.groovy")
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(
                coroutineScope = scope,
                compilationService = GroovyCompilationService(),
                options = GroovyTextDocumentServiceOptions(
                    documentProvider = DocumentProvider().apply { put(uri, source) },
                ),
            )
            val edits = service.formatting(
                DocumentFormattingParams(TextDocumentIdentifier(uri.toString()), FormattingOptions(4, true)),
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertThat(edits).hasSize(1)
            assertSafeFormatting(edits.single().newText)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `format code action preserves the annotated logging expression`() {
        val action = FormattingAction(OpenRewriteFormatterAdapter())
            .createFormattingAction("file:///LintBait.groovy", source)

        assertThat(action).isNotNull()
        val edits = checkNotNull(action?.edit?.changes?.get("file:///LintBait.groovy"))
        assertThat(edits).hasSize(1)
        assertSafeFormatting(edits.single().newText)
    }

    @Test
    fun `CLI formats the annotated logging expression`(@TempDir directory: Path) {
        val file = directory.resolve("LintBait.groovy").apply { writeText(source) }
        val previous = System.out
        val output = ByteArrayOutputStream()
        try {
            PrintStream(output, true, Charsets.UTF_8).use { captured ->
                System.setOut(captured)
                GlsCommand().parse(listOf("format", file.toString()))
            }
        } finally {
            System.setOut(previous)
        }

        assertSafeFormatting(output.toString(Charsets.UTF_8).trimEnd())
    }

    private fun assertSafeFormatting(formatted: String) {
        assertThat(formatted).isNotEqualTo(source)
        assertThat(formatted).contains("    String debug", "        log.debug(", "@Slf4j")
        assertThat(TokenStreamGuard.check(source, formatted).isSafe).isTrue()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
