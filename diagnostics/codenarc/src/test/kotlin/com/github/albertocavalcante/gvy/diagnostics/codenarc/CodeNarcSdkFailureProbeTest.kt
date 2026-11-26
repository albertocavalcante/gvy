// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codenarc.analyzer.AnalyzerException
import org.codenarc.analyzer.FilesSourceAnalyzer
import org.codenarc.rule.Violation
import org.codenarc.rule.formatting.FileEndsWithoutNewlineRule
import org.codenarc.ruleset.ListRuleSet
import org.codenarc.source.SourceCode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Temporary qualification probe: raw SDK identity failures are meaningful reds, not waived tests. */
class CodeNarcSdkFailureProbeTest {
    @TempDir
    lateinit var directory: Path

    @TestFactory
    fun `raw default must propagate the original failure`(): List<DynamicTest> = failures().map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val (rule, calls) = failingRule(failure)
            val raw = rawAnalyzer()
            val thrown = runCatching { raw.analyze(ListRuleSet(listOf(rule))) }.exceptionOrNull()
            assertEquals(1, calls.count, "raw rule must run before grading propagation")
            assertSame(failure, thrown, "raw default must not swallow or replace the failure")
        }
    }

    @TestFactory
    fun `raw failOnError must propagate the original failure`(): List<DynamicTest> = failures().map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val (rule, calls) = failingRule(failure)
            val raw = rawAnalyzer().apply { setFailOnError(true) }
            val thrown = runCatching { raw.analyze(ListRuleSet(listOf(rule))) }.exceptionOrNull()
            assertEquals(1, calls.count, "raw rule must run before grading propagation")
            assertSame(failure, thrown, "raw failOnError must not wrap or replace the failure")
        }
    }

    @TestFactory
    fun `raw default characterizes dropped file`(): List<DynamicTest> = failures().map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val (rule, calls) = failingRule(failure)
            val result = rawAnalyzer().analyze(ListRuleSet(listOf(rule)))
            assertEquals(1, calls.count)
            assertEquals(0, result.getTotalNumberOfFiles(true))
        }
    }

    @TestFactory
    fun `raw failOnError characterizes lost cause`(): List<DynamicTest> = failures().map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val (rule, calls) = failingRule(failure)
            val raw = rawAnalyzer().apply { setFailOnError(true) }
            val thrown = assertFailsWith<AnalyzerException> { raw.analyze(ListRuleSet(listOf(rule))) }
            assertEquals(1, calls.count)
            assertNull(thrown.cause)
        }
    }

    @TestFactory
    fun `adapter propagates original failure`(): List<DynamicTest> = failures().map { failure ->
        dynamicTest(failure.javaClass.simpleName) {
            val (rule, calls) = failingRule(failure)
            prepareSource()
            val adapter = SingleFileSourceAnalyzer(directory, FILE_NAME)
            val thrown = assertFails { adapter.analyze(ListRuleSet(listOf(rule))) }
            assertEquals(1, calls.count)
            assertSame(failure, thrown)
        }
    }

    private fun rawAnalyzer(): FilesSourceAnalyzer {
        prepareSource()
        return FilesSourceAnalyzer().apply {
            setBaseDirectory(directory.toString())
            setSourceFiles(FILE_NAME)
        }
    }

    private fun prepareSource() {
        Files.writeString(directory.resolve(FILE_NAME), "def value = 1\n")
    }

    private fun failingRule(failure: Throwable): Pair<FileEndsWithoutNewlineRule, Calls> {
        val calls = Calls()
        val rule = object : FileEndsWithoutNewlineRule() {
            override fun applyTo(sourceCode: SourceCode, violations: MutableList<Violation>) {
                calls.count++
                throw failure
            }
        }
        return rule to calls
    }

    private fun failures(): List<Throwable> = listOf(
        IOException("probe IO"),
        CancellationException("probe cancellation"),
        LinkageError("probe fatal"),
        IllegalStateException("probe ordinary failure"),
    )

    private class Calls(var count: Int = 0)

    private companion object {
        const val FILE_NAME = "Probe.groovy"
    }
}
