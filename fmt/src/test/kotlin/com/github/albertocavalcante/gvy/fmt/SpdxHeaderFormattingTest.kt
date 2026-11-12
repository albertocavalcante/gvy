// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SpdxHeaderFormattingTest {
    private val formatter = OpenRewriteFormatter()

    // REUSE-IgnoreStart
    private val header = """
        // SPDX-FileCopyrightText: Example Author
        // SPDX-License-Identifier: Apache-2.0
    """.trimIndent()
    // REUSE-IgnoreEnd

    @ParameterizedTest
    @ValueSource(strings = ["", "#!/usr/bin/env groovy\n"])
    fun `formats the body while retaining license comments and the shebang`(shebang: String) {
        val input = "$shebang$header\n\ndef first = 1\n  def second = 2"
        val normalizedShebang = if (shebang.isEmpty()) "" else "$shebang\n"
        val expected = "$normalizedShebang$header\n\ndef first = 1\ndef second = 2"

        val outcome = formatter.formatChecked(input)

        assertThat(outcome).isEqualTo(FormatOutcome.Formatted(expected))
        assertThat(formatter.formatChecked(outcome.text)).isEqualTo(FormatOutcome.Unchanged(expected))
    }

    @Test
    fun `retains a license before package and imports while formatting a class`() {
        val input = "$header\n\n" + """
            package example

            import java.util.List

            class Example {
            void run() {
            println 'hello'
            }
            }
        """.trimIndent()

        val outcome = formatter.formatChecked(input)

        assertThat(outcome).isInstanceOf(FormatOutcome.Formatted::class.java)
        assertThat(outcome.text).startsWith("$header\n\npackage example")
        assertThat(outcome.text).contains("    void run() {\n        println 'hello'")
        assertThat(formatter.formatChecked(outcome.text)).isEqualTo(FormatOutcome.Unchanged(outcome.text))
    }
}
