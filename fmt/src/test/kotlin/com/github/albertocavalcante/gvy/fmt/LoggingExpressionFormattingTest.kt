// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.tree.G

class LoggingExpressionFormattingTest {
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
    fun `raw parser preserves the transformed logging expression`() {
        val failures = mutableListOf<Throwable>()
        val context = InMemoryExecutionContext { failures.add(it) }
        val parsed = GroovyParser.builder().build().parse(context, source).use { it.toList() }

        assertThat(failures).isEmpty()
        assertThat(parsed).singleElement().isInstanceOf(G.CompilationUnit::class.java)
        assertThat(parsed.single().printAll()).isEqualTo(source)
    }

    @Test
    fun `formatter safely formats the transformed logging expression`() {
        val result = OpenRewriteFormatter().formatChecked(source)

        assertThat(result).isNotInstanceOf(FormatOutcome.Refused::class.java)
        assertThat(
            result.text,
        ).contains("@Slf4j", "log.debug(\"debug: ${'$'}{a} ${'$'}{b} ${'$'}{c} ${'$'}{d} ${'$'}{e}\")")
        assertThat(TokenStreamGuard.check(source, result.text)).isEqualTo(TokenStreamVerdict.Preserved)
    }
}
