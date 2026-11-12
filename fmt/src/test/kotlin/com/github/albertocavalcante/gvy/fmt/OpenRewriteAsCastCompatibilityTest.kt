// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.format.AutoFormat
import org.openrewrite.groovy.tree.G
import org.openrewrite.internal.InMemoryLargeSourceSet

/** Dependency-level regressions for the upstream as-cast fix tracked by #1598. */
class OpenRewriteAsCastCompatibilityTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "f(x as BigDecimal)",
            "new Date(x as long)",
            "def y = x as BigDecimal",
            "def y = x as List<String>",
            "def y = x as String[]",
        ],
    )
    fun `upstream AutoFormat preserves cast tokens without gvy post-processing`(source: String) {
        val context = InMemoryExecutionContext { throw AssertionError("OpenRewrite recipe failed", it) }
        val parsed = GroovyParser.builder().build().parse(context, source).toList()
        assertThat(parsed).hasSize(1)
        assertThat(parsed.single()).isInstanceOf(G.CompilationUnit::class.java)
        assertThat(parsed.single().printAll()).isEqualTo(source)

        val changes = AutoFormat().run(InMemoryLargeSourceSet(parsed), context).changeset.allResults
        assertThat(changes).hasSizeLessThanOrEqualTo(1)
        val formatted = if (changes.isEmpty()) {
            parsed.single().printAll()
        } else {
            requireNotNull(changes.single().after) { "Formatting must not delete the source" }.printAll()
        }

        assertThat(TokenStreamGuard.check(source, formatted)).isEqualTo(TokenStreamVerdict.Preserved)
        assertThat(formatted).contains(" as ")
    }

    @Test
    fun `a nested cast that upstream cannot round trip never damages the document`() {
        val source = "class A {\ndef f(Object x) {\ndef y = (x as List) as Set\n}\n}"

        val outcome = OpenRewriteFormatter().formatChecked(source)

        assertThat(TokenStreamGuard.check(source, outcome.text)).isEqualTo(TokenStreamVerdict.Preserved)
        assertThat(outcome.text).contains("(x as List) as Set")
    }

    @Test
    fun `gvy formats a document containing casts instead of refusing it`() {
        val source = "class A {\ndef f(Object x) {\ndef y = x as BigDecimal\nnew Date(x as long)\n}\n}"

        val outcome = OpenRewriteFormatter().formatChecked(source)

        assertThat(outcome).isInstanceOf(FormatOutcome.Formatted::class.java)
        assertThat(outcome.text).isEqualTo(
            "class A {\n    def f(Object x) {\n        def y = x as BigDecimal\n        new Date(x as long)\n    }\n}",
        )
        assertThat(OpenRewriteFormatter().formatChecked(outcome.text)).isEqualTo(FormatOutcome.Unchanged(outcome.text))
    }
}
