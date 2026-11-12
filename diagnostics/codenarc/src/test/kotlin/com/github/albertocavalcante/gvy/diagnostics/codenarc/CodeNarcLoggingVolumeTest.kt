// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeNarcLoggingVolumeTest {
    @Test
    fun `repeated analyses keep results without info chatter`() {
        val logger = LoggerFactory.getLogger(CodeNarcDiagnosticProvider::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val provider = provider()
            for (level in listOf(Level.INFO, Level.DEBUG)) {
                logger.level = level
                repeat(3) {
                    val diagnostics = provider.analyzeAndGetDiagnostics("class Example {}", "Example.groovy")
                    assertEquals(1, diagnostics.size)
                    assertEquals("TestRule", diagnostics.single().code.left)
                }
                val completed = appender.list.filter { it.formattedMessage.contains("CodeNarc analysis completed") }
                if (level == Level.INFO) {
                    assertTrue(completed.isEmpty())
                } else {
                    assertEquals(3, completed.size)
                    assertTrue(completed.all { it.level == Level.DEBUG && it.formattedMessage.length < 512 })
                }
                appender.list.clear()
            }
        } finally {
            logger.level = previous
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun provider(): CodeNarcDiagnosticProvider {
        val rule = mockk<Rule>()
        every { rule.name } returns "TestRule"
        every { rule.priority } returns 3
        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 1
        every { violation.message } returns "test violation"
        val results = mockk<Results>()
        every { results.children } returns mutableListOf()
        every { results.violations } returns mutableListOf(violation)
        val analyzer = object : CodeAnalyzer {
            override fun analyze(
                sourceCode: String,
                fileName: String,
                rulesetContent: String,
                propertiesFile: String?,
            ) = results
        }
        val workspace = object : WorkspaceContext {
            override val root: Path? = null
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = false
            }
        }
        val resolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext) = RulesetConfiguration("ruleset { }")
        }
        return CodeNarcDiagnosticProvider(workspace, rulesetResolver = resolver, codeAnalyzer = analyzer)
    }
}
