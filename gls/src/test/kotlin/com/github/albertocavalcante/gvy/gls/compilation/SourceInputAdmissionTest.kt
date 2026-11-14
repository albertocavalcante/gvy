// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceInputAdmissionTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `one request deduplicates path and URI admission while a later request observes current policy`() {
        val source = Files.writeString(root.resolve("Money.groovy"), "class Money {}")
        var checks = 0
        var managed = false
        val excluded: (Path) -> Boolean = {
            checks++
            managed
        }
        val admission = SourceInputAdmission(excluded) {}
        assertTrue(admission.allows(source))
        val observed = checks
        repeat(100) {
            assertTrue(admission.allows(source.toUri()))
            assertTrue(admission.allows(source.parent.resolve("./Money.groovy")))
        }
        assertEquals(
            observed,
            checks,
            "Repeated class-locator and secondary input references share one admission decision",
        )

        managed = true
        assertFalse(SourceInputAdmission(excluded) {}.allows(source), "No observation is cached across requests")
    }

    @Test
    fun `parent traversal cannot reuse an ordinary cached admission`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed")
        val managed = Files.createDirectory(root.resolve("managed"))
        val child = Files.createDirectory(managed.resolve("child"))
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val link = Files.createSymbolicLink(workspace.resolve("link"), child)
        val traversed = link.resolve("../Mode.groovy")
        val admission = SourceInputAdmission({ it.startsWith(managed) }) {}

        assertTrue(admission.allows(workspace.resolve("Mode.groovy")))
        assertFalse(admission.allows(traversed))
        assertFalse(admission.allows(traversed.toUri()))
        assertFailsWith<IllegalArgumentException> { admission.requireDocument(traversed.toUri()) }
    }

    @Test
    fun `cached admission still checks cancellation and ordinary unsaved files remain admissible`() {
        val unsaved = root.resolve("new/nested/Money.groovy")
        val cancellation = CancellationException("cancelled")
        var cancelled = false
        val admission = SourceInputAdmission({ false }) { if (cancelled) throw cancellation }
        assertTrue(admission.allows(unsaved))
        cancelled = true

        assertSame(cancellation, assertFailsWith<CancellationException> { admission.allows(unsaved) })
    }
}
