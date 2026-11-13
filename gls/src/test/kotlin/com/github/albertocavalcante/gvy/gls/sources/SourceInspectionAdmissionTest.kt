// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.javaparser.JavaParser
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@Timeout(10)
class SourceInspectionAdmissionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Java source inspection rejects oversized inputs before parser creation`() {
        var parsers = 0
        val inspector = JavaSourceInspector {
            parsers++
            error("Oversized source must not create a parser")
        }
        val source = "class Large {}" + " ".repeat(1024 * 1024)
        assertNull(inspector.inspectDefinitionFromContent(source, "Large"))
        assertNull(inspector.inspectMethodFromContent(source, "Large", "method"))
        assertEquals(emptyMap(), inspector.extractAllMethodParametersFromContent(source, "Large"))
        assertEquals(0, parsers)
    }

    @Test
    fun `public Java path inspection refuses oversized bytes before parser allocation`() {
        var parsers = 0
        val inspector = JavaSourceInspector {
            parsers++
            error("Oversized source must not create a parser")
        }
        val source = directory.resolve("Large.java")
        Files.writeString(source, "class Large {}" + " ".repeat(4 * 1024 * 1024))
        assertNull(inspector.inspectClass(source, "Large"))
        assertNull(inspector.inspectMethod(source, "Large", "method"))
        assertEquals(emptyMap(), inspector.extractAllMethodParameters(source, "Large"))
        assertEquals(0, parsers)
    }

    @Test
    fun `Java lexing preserves cancellation after parser entry`() {
        var parsers = 0
        var checks = 0
        val cancelled = CancellationException("stop Java source lexing")
        val inspector = JavaSourceInspector {
            parsers++
            JavaParser()
        }
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                inspector.inspectDefinitionFromContent("class Example {}", "Example", checkCancelled = {
                    if (++checks == 2) throw cancelled
                })
            },
        )
        assertEquals(1, parsers)
        assertEquals(2, checks)
    }

    @Test
    fun `Groovy declaration inspection propagates cancellation inside the native compile phase`() {
        var checks = 0
        val cancelled = CancellationException("stop Groovy source conversion")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                GroovySourceDeclarationInspector().inspect(
                    "class Example {}",
                    "Example",
                    URI.create("file:///Example.groovy"),
                ) {
                    if (++checks == 3) throw cancelled
                }
            },
        )
        assertEquals(3, checks)
    }

    @Test
    fun `oversized Groovy declarations remain file-only without entering native parsing`() {
        var checks = 0
        assertNull(
            GroovySourceDeclarationInspector().inspect(
                "class Large {}" + " ".repeat(1024 * 1024),
                "Large",
                URI.create("file:///Large.groovy"),
            ) {
                checks++
                if (checks > 1) error("Oversized Groovy source entered native parsing")
            },
        )
        assertEquals(1, checks)
    }

    @Test
    fun `unclosed documentation markup is literal and does not repeatedly scan the same suffix`() {
        val source = "/** " + "<".repeat(100_000) + " */\nclass Example {}"
        val result = assertNotNull(JavaSourceInspector().inspectClassFromContent(source, "Example"))
        assertEquals("<".repeat(200), result.documentation.summary)
    }
}
