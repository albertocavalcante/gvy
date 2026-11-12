// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gdsl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The AST interpreter reads trusted declarative GDSL exactly as the former GroovyShell executor did.
 * The `*.golden.txt` fixtures were captured from that executor (before it was removed) on these
 * trusted files; they no longer depend on it.
 */
class GdslGoldenTest {
    @ParameterizedTest
    @CsvSource(
        "/gdsl-golden/jenkins-sample.gdsl, /gdsl-golden/jenkins-sample.golden.txt",
        "/bundled-gdsls/spock.gdsl, /gdsl-golden/spock.golden.txt",
        "/bundled-gdsls/grails.gdsl, /gdsl-golden/grails.golden.txt",
    )
    fun `interpreter output matches the captured executor output`(script: String, golden: String) {
        val text = requireNotNull(javaClass.getResource(script)) { script }.readText()
        val expected = requireNotNull(javaClass.getResource(golden)) { golden }.readText()

        val result = GdslExecutor().executeAndCapture(text, script.substringAfterLast('/'))

        assertThat(GdslGolden.render(result)).isEqualTo(expected)
    }
}
