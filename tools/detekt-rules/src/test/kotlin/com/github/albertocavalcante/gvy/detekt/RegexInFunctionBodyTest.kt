// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegexInFunctionBodyTest {
    private val rule = RegexInFunctionBody()

    @Test
    fun `flags every marked construction in the violations fixture`() {
        rule.assertReportsMarkedLines("regex-in-function-body/violations.kt.fixture")
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        rule.assertReportsNothing("regex-in-function-body/compliant.kt.fixture")
    }

    @Test
    fun `message names the construction and the fix`() {
        val findings = rule.lint("fun f() = Regex(\"a+\")")
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).startsWith("`Regex(...)` compiles ").contains("Hoist it")
    }
}
