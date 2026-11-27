// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat

/** Ends each line of a fixture that the rule must report. */
private const val FLAG_MARKER = "// FLAG"

/** Anchors resource lookups in the test class loader. */
private object FixtureAnchor

/** The text of the test resource at [path], such as `jdk-atomics/violations.kt.fixture`. */
internal fun fixture(path: String): String = requireNotNull(FixtureAnchor::class.java.getResource("/$path")) {
    "missing fixture $path"
}.readText()

/** The 1-based lines at which this rule reports a finding in [code], in ascending order. */
internal fun Rule.flaggedLines(code: String): List<Int> = lint(code).map { it.entity.location.source.line }.sorted()

/** The 1-based lines of [source] that carry the `// FLAG` marker. */
internal fun markedLines(source: String): List<Int> =
    source.lines().withIndex().filter { (_, line) -> line.contains(FLAG_MARKER) }.map { (index, _) -> index + 1 }

/** Asserts that the fixture at [path] marks some lines and that this rule reports exactly those. */
internal fun Rule.assertReportsMarkedLines(path: String) {
    val source = fixture(path)
    assertThat(markedLines(source)).describedAs("lines marked in $path").isNotEmpty()
    assertThat(flaggedLines(source)).describedAs("lines reported in $path").isEqualTo(markedLines(source))
}

/** Asserts that the fixture at [path] marks no line and that this rule reports nothing in it. */
internal fun Rule.assertReportsNothing(path: String) {
    val source = fixture(path)
    assertThat(markedLines(source)).describedAs("lines marked in $path").isEmpty()
    assertThat(flaggedLines(source)).describedAs("lines reported in $path").isEmpty()
}
