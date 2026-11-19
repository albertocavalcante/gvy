// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RulesetFailureOutcomeTest {
    @TempDir
    lateinit var root: Path

    @TestFactory
    fun `packaged resource faults cannot select a healthy fallback`(): List<DynamicTest> = failures().map { failure ->
        DynamicTest.dynamicTest("packaged resource propagates ${failure.javaClass.simpleName}") {
            val resolver = HierarchicalRulesetResolver(resourceLoader = ResourceLoader { throw failure })

            assertSame(failure, assertFails { resolver.resolve(workspace()) })
        }
    }

    @TestFactory
    fun `workspace read faults cannot become a policy rejected ruleset`(): List<DynamicTest> =
        failures().map { failure ->
            DynamicTest.dynamicTest("workspace opening propagates ${failure.javaClass.simpleName}") {
                Files.writeString(root.resolve("codenarc.groovy"), VALID_RULESET)
                val resolver = HierarchicalRulesetResolver(openWorkspaceRuleset = { throw failure })

                assertSame(failure, assertFails { resolver.resolve(workspace(root)) })
            }
        }

    @Test
    fun `an absent optional packaged profile still uses the bundled ruleset`() {
        val resolver = HierarchicalRulesetResolver(resourceLoader = ResourceLoader { null })

        assertEquals("bundled:rulesets/basic.xml", resolver.resolve(workspace()).source)
    }

    @Test
    fun `policy rejected workspace syntax still reports its ignored file`() {
        Files.writeString(root.resolve("codenarc.groovy"), "new File('forbidden').delete()")

        val result = HierarchicalRulesetResolver().resolve(workspace(root))

        assertEquals(listOf("codenarc.groovy"), result.ignoredWorkspaceRulesets)
        assertEquals("resource:codenarc/rulesets/base/default.groovy", result.source)
    }

    @Test
    fun `a ruleset growing after the size check is read only through the byte limit sentinel`() {
        Files.writeString(root.resolve("codenarc.groovy"), VALID_RULESET)
        val stream =
            CountingStream(ByteArray((WorkspaceRulesetInterpreter.MAX_FILE_BYTES * 2).toInt()) { ' '.code.toByte() })
        val resolver = HierarchicalRulesetResolver(openWorkspaceRuleset = { stream })

        val result = resolver.resolve(workspace(root))

        assertEquals(listOf("codenarc.groovy"), result.ignoredWorkspaceRulesets)
        assertEquals(WorkspaceRulesetInterpreter.MAX_FILE_BYTES + 1, stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test
    fun `a valid workspace ruleset is fully read and closed`() {
        Files.writeString(root.resolve("codenarc.groovy"), VALID_RULESET)
        val stream = CountingStream(VALID_RULESET.toByteArray())
        val resolver = HierarchicalRulesetResolver(openWorkspaceRuleset = { stream })

        val result = resolver.resolve(workspace(root))

        assertEquals("workspace:codenarc.groovy", result.source)
        assertTrue(result.ignoredWorkspaceRulesets.isEmpty())
        assertEquals(VALID_RULESET.toByteArray().size.toLong(), stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test
    fun `a large valid multibyte ruleset is fully read and accepted`() {
        val text = "//" + "\u2603".repeat(LARGE_COMMENT_CHARS) + "\n" + VALID_RULESET
        Files.writeString(root.resolve("codenarc.groovy"), text)
        val stream = CountingStream(text.toByteArray())
        val resolver = HierarchicalRulesetResolver(openWorkspaceRuleset = { stream })

        val result = resolver.resolve(workspace(root))

        assertEquals("workspace:codenarc.groovy", result.source)
        assertTrue(result.ignoredWorkspaceRulesets.isEmpty())
        assertEquals(text.toByteArray().size.toLong(), stream.bytesRead)
        assertTrue(stream.closed)
    }

    @TestFactory
    fun `faults during a workspace read propagate and close the stream`(): List<DynamicTest> =
        failures().map { failure ->
            DynamicTest.dynamicTest("workspace reading propagates ${failure.javaClass.simpleName}") {
                Files.writeString(root.resolve("codenarc.groovy"), VALID_RULESET)
                var closed = false
                val stream = object : java.io.InputStream() {
                    override fun read(): Int = throw failure
                    override fun close() {
                        closed = true
                    }
                }
                val resolver = HierarchicalRulesetResolver(openWorkspaceRuleset = { stream })

                assertSame(failure, assertFails { resolver.resolve(workspace(root)) })
                assertTrue(closed)
            }
        }

    private fun failures(): List<Throwable> = listOf(
        IOException("ruleset unreadable"),
        CancellationException("ruleset cancelled"),
        LinkageError("ruleset implementation unavailable"),
    )

    private fun workspace(path: Path? = null): WorkspaceContext = object : WorkspaceContext {
        override val root = path
        override fun getConfiguration() = object : DiagnosticConfiguration {
            override val isEnabled = true
            override val propertiesFile: String? = null
            override val autoDetectConfig = false
        }
    }

    private class CountingStream(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(): Int = `in`.read().also { if (it >= 0) bytesRead++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, length).also { if (it > 0) bytesRead += it }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val VALID_RULESET = "ruleset { TrailingWhitespace }"
        const val LARGE_COMMENT_CHARS = 60_000
    }
}
