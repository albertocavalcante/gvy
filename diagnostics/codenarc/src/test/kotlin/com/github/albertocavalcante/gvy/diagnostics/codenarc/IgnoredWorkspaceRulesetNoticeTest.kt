// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "workspace ruleset is ignored" notice belongs on the ignored file only, not on every analysed file. */
class IgnoredWorkspaceRulesetNoticeTest {
    @TempDir
    lateinit var tempDir: Path

    private fun provider(root: Path) = CodeNarcDiagnosticProvider(
        object : WorkspaceContext {
            override val root: Path = root
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = true
            }
        },
    )

    @Test
    fun `ignored ruleset notice links affected sources to the configuration`() = runBlocking {
        val ruleset = tempDir.resolve("codenarc.groovy")
        Files.writeString(ruleset, "ruleset { evaluate 'println 1' }")
        val other = tempDir.resolve("Other.groovy")
        Files.writeString(other, "class Other {}\n")
        val provider = provider(tempDir)

        val onRuleset = provider.analyze("ruleset { evaluate 'println 1' }\n", ruleset.toUri())
        val onOther = provider.analyze("class Other {}\n", other.toUri())

        val notices = onRuleset.filter {
            it.severity == DiagnosticSeverity.Information &&
                it.code?.left == "gvy.providerFailure"
        }
        assertEquals(1, notices.size, "expected one notice on the ruleset file, got ${onRuleset.map { it.message }}")
        val sourceNotice = onOther.single {
            it.severity == DiagnosticSeverity.Information &&
                it.code?.left == "gvy.providerFailure"
        }
        assertEquals(ruleset.toUri().toString(), sourceNotice.relatedInformation.single().location.uri)
        assertTrue(sourceNotice.message.contains("codenarc.groovy"))
        assertTrue(sourceNotice.message.contains("workspace configuration"))
        assertTrue(!sourceNotice.message.contains("ruleset reference"))
    }

    @Test
    fun `oversized configuration reports a filewide rejection without a literal claim`() = runBlocking {
        val ruleset = tempDir.resolve(".codenarc")
        Files.write(ruleset, ByteArray((WorkspaceRulesetInterpreter.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() })
        val diagnostics = provider(tempDir).analyze("class Other {}\n", tempDir.resolve("Other.groovy").toUri())
        val notice = diagnostics.single { it.code?.left == "gvy.providerFailure" }
        assertTrue(notice.message.contains("larger than"))
        assertTrue(notice.message.contains("workspace configuration"))
        assertEquals(ruleset.toUri().toString(), notice.relatedInformation.single().location.uri)
        assertEquals(
            notice.relatedInformation.single().location.range.start,
            notice.relatedInformation.single().location.range.end,
        )
    }

    @Test
    fun `the user is told once per ignored ruleset file`() = runBlocking {
        Files.writeString(tempDir.resolve("codenarc.groovy"), "ruleset { evaluate 'println 1' }")
        val other = tempDir.resolve("Other.groovy")
        Files.writeString(other, "class Other {}\n")
        val told = mutableListOf<String>()
        val provider = CodeNarcDiagnosticProvider(
            object : WorkspaceContext {
                override val root: Path = tempDir
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
            onWorkspaceRulesetIgnored = { told.add(it) },
        )

        provider.analyze("class Other {}\n", other.toUri())
        Files.writeString(tempDir.resolve("codenarc.groovy"), "ruleset { ruleset('rulesets/empty.xml') }")
        provider.analyze("class Other {}\n", other.toUri())
        Files.writeString(tempDir.resolve("codenarc.groovy"), "ruleset { ruleset('rulesets/basic.xml') }")
        provider.analyze("class Other {}\n", other.toUri())
        Files.writeString(tempDir.resolve("codenarc.groovy"), "ruleset { evaluate 'println 2' }")
        provider.analyze("class Other {}\n", other.toUri())

        assertEquals(listOf("codenarc.groovy"), told)
    }

    @Test
    fun `rejected higher priority ruleset falls back to accepted lower priority file`() = runBlocking {
        val rejected = tempDir.resolve(".codenarc")
        Files.writeString(rejected, "ruleset { ruleset('rulesets/empty.xml') }")
        Files.writeString(
            tempDir.resolve("codenarc.groovy"),
            "ruleset { ruleset('rulesets/imports.xml') { include 'UnusedImport' } }",
        )
        val resolver = HierarchicalRulesetResolver()
        val context = object : WorkspaceContext {
            override val root: Path = tempDir
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = true
            }
        }
        val result = resolver.resolveDetailed(context, tempDir.resolve("Other.groovy").toUri())
        assertEquals("workspace:codenarc.groovy", result.configuration.source)
        assertEquals(listOf(".codenarc"), result.configuration.ignoredWorkspaceRulesets)
        val notice = provider(tempDir).analyze("class Other {}\n", tempDir.resolve("Other.groovy").toUri())
            .single { it.code?.left == "gvy.providerFailure" }
        assertTrue(notice.message.contains(".codenarc"))
        assertEquals(rejected.toUri().toString(), notice.relatedInformation.single().location.uri)
    }

    @Test
    fun `missing bundled ruleset falls back and its diagnostic clears after correction`() = runBlocking {
        val ruleset = tempDir.resolve("codenarc.groovy")
        val invalid = "ruleset { ruleset('rulesets/imports.xml'); ruleset('rulesets/empty.xml') }"
        Files.writeString(ruleset, invalid)
        val provider = provider(tempDir)

        val rejected = provider.analyze(invalid, ruleset.toUri())
        val notice = rejected.single {
            it.severity == DiagnosticSeverity.Information &&
                it.code?.left == "gvy.providerFailure"
        }
        assertTrue(notice.message.contains("rulesets/empty.xml"), notice.message)

        val corrected = "ruleset { ruleset('rulesets/basic.xml') }"
        Files.writeString(ruleset, corrected)
        val accepted = provider.analyze(corrected, ruleset.toUri())
        assertTrue(
            accepted.none {
                it.severity == DiagnosticSeverity.Information &&
                    it.code?.left == "gvy.providerFailure"
            },
        )
    }

    @Test
    fun `missing bundled ruleset reports its reason and uses the default profile`() = runBlocking {
        val ruleset = tempDir.resolve(".codenarc")
        Files.writeString(ruleset, "ruleset { ruleset('rulesets/imports.xml'); ruleset('rulesets/empty.xml') }")
        val context = object : WorkspaceContext {
            override val root: Path = tempDir
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = true
            }
        }
        val resolver = HierarchicalRulesetResolver()
        val resolved = resolver.resolve(context)
        assertEquals("resource:codenarc/rulesets/base/default.groovy", resolved.source)
        assertEquals(listOf(".codenarc"), resolved.ignoredWorkspaceRulesets)
        val notices = mutableListOf<Pair<String, String?>>()
        val provider = CodeNarcDiagnosticProvider(
            context,
            rulesetResolver = resolver,
        ).also { it.onWorkspaceRulesetsResolved = { _, rejected -> notices.addAll(rejected.toList()) } }
        val source = "import java.util.concurrent.TimeUnit\nclass Example {}\n"
        val uri = tempDir.resolve("Example.groovy").toUri()

        val diagnostics = provider.analyze(source, uri)
        provider.analyze(source, uri)

        assertTrue(diagnostics.any { it.source == "CodeNarc" }, diagnostics.toString())
        assertEquals(2, notices.size)
        assertEquals(".codenarc", notices.first().first)
        assertTrue(notices.first().second.orEmpty().contains("rulesets/empty.xml"))

        Files.writeString(ruleset, "ruleset { ruleset('rulesets/basic.xml') }")
        provider.analyze(source, uri)
        Files.writeString(ruleset, "ruleset { ruleset('rulesets/missing.xml') }")
        provider.analyze(source, uri)
        assertEquals(3, notices.size)
        assertTrue(notices.last().second.orEmpty().contains("rulesets/missing.xml"))
    }
}
