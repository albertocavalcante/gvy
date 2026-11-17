// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.openrewrite.ExecutionContext
import org.openrewrite.SourceFile

/** Operators that rewrite-groovy stores inside whitespace must survive formatting (#1609). */
class HiddenSpaceTextRestorerTest {

    private val formatter = OpenRewriteFormatter()

    private fun assertFormattedKeepingTokens(input: String) {
        val outcome = formatter.formatChecked(input)

        // Not `format`: a refusal returns the input, which would pass for the wrong reason.
        assertThat(outcome)
            .withFailMessage { "Expected <$input> to be Formatted but got $outcome" }
            .isInstanceOf(FormatOutcome.Formatted::class.java)
        assertThat(TokenStreamGuard.check(input, outcome.text).isSafe)
            .withFailMessage { "Tokens changed for <$input>: <${outcome.text}>" }
            .isTrue()
        // formatChecked, not format: a refusal of the second pass must not pass as "unchanged".
        assertThat(formatter.formatChecked(outcome.text))
            .withFailMessage { "Formatting <${outcome.text}> again did not leave it unchanged" }
            .isEqualTo(FormatOutcome.Unchanged(outcome.text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "def   b  =   ~a\n",
            "def   y  =   -x as int\n",
            "def   x  =   3.303e+23\n",
            "class A {\n      def f() {\n        def   b  =   ~a\n      }\n}\n",
        ],
    )
    fun `formats source whose operator or literal part sits in whitespace`(input: String) {
        assertFormattedKeepingTokens(input)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "def   a  =   -x\n",
            "def   a  =   +x\n",
            "def   a  =   -(x + 1)\n",
            "def   a  =   -foo()\n",
            "def   a  =   !x\n",
            "def   a  =   1.5\n",
        ],
    )
    fun `still formats ordinary unary operators and numbers without duplicating them`(statement: String) {
        val input = "class A {\n      def f() {\n$statement      }\n}\n"

        assertFormattedKeepingTokens(input)
    }

    @ParameterizedTest
    @ValueSource(strings = ["def   b  =   ~a\n", "def   y  =   -x as int\n", "def   x  =   3.303e+23\n"])
    fun `refuses when the formatted tree no longer matches the recorded nodes`(input: String) {
        val operations = object : RewriteOperations() {
            // Re-parsing the formatted text gives every node a new identity, so nothing can be restored.
            override fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? =
                super.rewrite(sources, context)?.let { parse(it.printAll(), context).first() }
        }

        val outcome = OpenRewriteFormatter(TokenStreamGuard::check, operations).formatChecked(input)

        assertThat(outcome)
            .withFailMessage { "Expected a refusal for <$input> but got $outcome" }
            .isInstanceOf(FormatOutcome.Refused::class.java)
        assertThat(outcome.text).isEqualTo(input)
        assertThat((outcome as FormatOutcome.Refused).reason).contains("first difference")
    }
}
