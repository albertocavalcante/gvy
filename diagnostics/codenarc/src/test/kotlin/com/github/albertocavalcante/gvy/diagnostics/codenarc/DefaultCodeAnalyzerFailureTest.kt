// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DefaultCodeAnalyzerFailureTest {
    @TestFactory
    fun `real formatting rule failures on valid carriage return source cannot become completed empty analysis`():
        List<DynamicTest> =
        listOf(
            "def first = 1\rdef second = 2\r",
            "def first = 1;\rdef second = 2\r",
        ).map { source ->
            dynamicTest(source.replace("\r", "\\r")) {
                // These are accepted Groovy inputs; pinned CodeNarc's formatting rules mishandle their positions.
                SourceUnit.create(FILE_NAME, source).apply {
                    parse()
                    completePhase()
                    convert()
                }

                val failure = assertFailsWith<CodeAnalysisException> {
                    DefaultCodeAnalyzer().analyze(source, FILE_NAME, FORMATTING_RULES)
                }

                assertIs<StringIndexOutOfBoundsException>(failure.cause)
            }
        }

    private companion object {
        const val FILE_NAME = "Example.groovy"
        const val FORMATTING_RULES = "ruleset { ruleset('rulesets/formatting.xml') }"
    }
}
