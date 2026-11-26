// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.FileState
import com.github.albertocavalcante.nativeapi.NativeSourceMembership.Status
import com.github.albertocavalcante.nativeapi.SourceMembershipRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeSourceMembershipTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a verified local miss retires only when a consulted resource appears`() {
        val recorder = SourceMembershipRecorder(listOf(root))
        recorder.scriptSearch { recorder.resource("Shadow.groovy") { null } }
        val membership = recorder.finish()
        Files.writeString(root.resolve("Unrelated.groovy"), "class Unrelated {}")
        assertEquals(Status.CURRENT, membership.check {})
        Files.writeString(root.resolve("Shadow.groovy"), "class Shadow {}")
        assertEquals(Status.CHANGED, membership.check {})
    }

    @Test
    fun `a candidate changing during the actual selection cannot establish membership`() {
        val recorder = SourceMembershipRecorder(listOf(root))
        recorder.scriptSearch {
            recorder.resource("Shadow.groovy") {
                Files.writeString(root.resolve("Shadow.groovy"), "class Shadow {}").toUri().toURL()
            }
        }
        assertEquals(Status.CHANGED, recorder.finish().check {})
    }

    @Test
    fun `a null loader answer with unknown physical state is unavailable`() {
        var called = false
        val recorder = SourceMembershipRecorder(listOf(root), state = { null })
        recorder.scriptSearch {
            recorder.resource("Shadow.groovy") {
                called = true
                null
            }
        }
        assertTrue(called, "evidence failure must preserve the compiler's lookup")
        assertEquals(Status.UNAVAILABLE, recorder.finish().check {})
    }

    @Test
    fun `a selected first directory does not depend on a hidden later file`() {
        val first = Files.createDirectories(root.resolve("first"))
        val second = Files.createDirectories(root.resolve("second"))
        val selected = Files.writeString(first.resolve("Shadow.groovy"), "class Shadow {}")
        val recorder = SourceMembershipRecorder(listOf(first, second))
        recorder.scriptSearch { recorder.resource("Shadow.groovy") { selected.toUri().toURL() } }
        val membership = recorder.finish()
        Files.writeString(second.resolve("Shadow.groovy"), "class Shadow {}")
        assertEquals(Status.CURRENT, membership.check {})
        Files.delete(selected)
        assertEquals(Status.CHANGED, membership.check {})
    }

    @Test
    fun `selection bypassing an earlier present file is ambiguous`() {
        val first = Files.createDirectories(root.resolve("first"))
        val second = Files.createDirectories(root.resolve("second"))
        Files.writeString(first.resolve("Shadow.groovy"), "class Shadow {}")
        val selected = Files.writeString(second.resolve("Shadow.groovy"), "class Shadow {}")
        val recorder = SourceMembershipRecorder(listOf(first, second))
        recorder.scriptSearch { recorder.resource("Shadow.groovy") { selected.toUri().toURL() } }
        assertEquals(Status.UNAVAILABLE, recorder.finish().check {})
    }

    @Test
    fun `a hidden existing file and an over-budget inventory cannot certify absence`() {
        Files.writeString(root.resolve("Hidden.groovy"), "class Hidden {}")
        val hidden = SourceMembershipRecorder(listOf(root))
        hidden.scriptSearch { hidden.resource("Hidden.groovy") { null } }
        assertEquals(Status.UNAVAILABLE, hidden.finish().check {})

        val bounded = SourceMembershipRecorder(listOf(root), limit = 1, state = { FileState.Absent })
        for (name in listOf("First.groovy", "Second.groovy")) {
            bounded.scriptSearch { bounded.resource(name) { null } }
        }
        assertEquals(Status.UNAVAILABLE, bounded.finish().check {})
    }
}
