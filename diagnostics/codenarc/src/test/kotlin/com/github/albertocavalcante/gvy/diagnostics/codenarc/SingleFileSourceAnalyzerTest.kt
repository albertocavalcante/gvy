// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codenarc.CodeNarcRunner
import org.codenarc.analyzer.FilesSourceAnalyzer
import org.codenarc.plugin.AbstractCodeNarcPlugin
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.codenarc.rule.formatting.FileEndsWithoutNewlineRule
import org.codenarc.ruleset.ListRuleSet
import org.codenarc.source.SourceCode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

class SingleFileSourceAnalyzerTest {
    @TempDir
    lateinit var directory: Path

    @TestFactory
    fun `real runner preserves ordinary cancellation and fatal rule failure identity`(): List<DynamicTest> = listOf(
        IOException("rule failed"),
        CancellationException("rule cancelled"),
        LinkageError("rule unavailable"),
    ).map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val sourceDirectory = source("def value = 1\n")
            val rule = object : FileEndsWithoutNewlineRule() {
                override fun applyTo(sourceCode: SourceCode, violations: MutableList<Violation>): Unit = throw failure
            }
            val rulesetFile = sourceDirectory.resolve("ruleset.groovy")
            Files.writeString(rulesetFile, "ruleset { FileEndsWithoutNewline }")
            val runner = CodeNarcRunner().apply {
                ruleSetFiles = rulesetFile.toUri().toString()
                sourceAnalyzer = SingleFileSourceAnalyzer(sourceDirectory, FILE_NAME)
                registerPlugin(object : AbstractCodeNarcPlugin() {
                    override fun processRules(rules: MutableList<Rule>) {
                        rules.clear()
                        rules.add(rule)
                    }
                })
            }

            assertSame(failure, assertFails { runner.execute() })
        }
    }

    @TestFactory
    fun `empty clean and syntax invalid source retain the SDK result tree`(): List<DynamicTest> = listOf(
        "",
        "def value = 1\n",
        "class Example { def broken = }",
    ).map { text ->
        dynamicTest(text.ifEmpty { "empty" }) {
            val sourceDirectory = source(text)
            val reference = FilesSourceAnalyzer().apply {
                setBaseDirectory(sourceDirectory.toString())
                setSourceFiles(FILE_NAME)
            }
            val expected = reference.analyze(ListRuleSet(listOf(FileEndsWithoutNewlineRule())))
            val analyzer = SingleFileSourceAnalyzer(sourceDirectory, FILE_NAME)
            val actual = analyzer.analyze(ListRuleSet(listOf(FileEndsWithoutNewlineRule())))

            assertEquals(reference.getSourceDirectories(), analyzer.getSourceDirectories())
            assertEquals(snapshot(expected), snapshot(actual))
            assertEquals(1, actual.getTotalNumberOfFiles(true))
        }
    }

    @Test
    fun `file name selectors retain matching and excluded file behavior`() {
        val sourceDirectory = source("def value = 1")
        val analyzer = SingleFileSourceAnalyzer(sourceDirectory, FILE_NAME)
        val matching = FileEndsWithoutNewlineRule().apply { setApplyToFileNames(FILE_NAME) }
        val excludedByPattern = FileEndsWithoutNewlineRule().apply { setApplyToFileNames("Other.groovy") }
        val excludedByName = FileEndsWithoutNewlineRule().apply { setDoNotApplyToFileNames(FILE_NAME) }

        val matchingResult = analyzer.analyze(ListRuleSet(listOf(matching)))
        assertEquals(1, matchingResult.getNumberOfViolationsWithPriority(LOW_PRIORITY, true))
        assertEquals(FILE_NAME, matchingResult.children.single().children.single().path)
        listOf(excludedByPattern, excludedByName).forEach { rule ->
            val result = analyzer.analyze(ListRuleSet(listOf(rule)))

            assertEquals(1, result.getTotalNumberOfFiles(true))
            assertEquals(0, result.getNumberOfViolationsWithPriority(LOW_PRIORITY, true))
        }
    }

    @Test
    fun `runner comment suppression retains the source metadata it needs`() {
        val result = DefaultCodeAnalyzer().analyze(
            "// codenarc-disable TrailingWhitespace\ndef value = 1  \n",
            FILE_NAME,
            "ruleset { TrailingWhitespace }",
        )

        assertEquals(1, result.getTotalNumberOfFiles(true))
        assertEquals(0, result.getNumberOfViolationsWithPriority(LOW_PRIORITY, true))
    }

    private fun source(text: String): Path = Files.createTempDirectory(directory, "analysis-").also {
        Files.writeString(it.resolve(FILE_NAME), text)
    }

    private fun snapshot(results: Results): List<String> = listOf(
        "${results.path}:${results.isFile()}:${results.getTotalNumberOfFiles(true)}",
        results.violations.joinToString { "${it.rule.name}:${it.lineNumber}:${it.message}" },
    ) + results.children.flatMap(::snapshot)

    private companion object {
        const val FILE_NAME = "ProjectFile.groovy"
        const val LOW_PRIORITY = 3
    }
}
