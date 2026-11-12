// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fence keeps generation state only for files with work in flight (#1341). These tests drive collection
 * through [ArtifactOwnership.clearUnreferencedStateForTesting], so they never depend on the garbage collector.
 */
class ArtifactOwnershipStateTest {
    private val ownership = ArtifactOwnership()

    private fun uri(i: Int) = URI.create("file:///State$i.groovy")

    @Test
    fun `retiring files with no work in flight keeps no state once their tickets are gone`() {
        repeat(FILES) { ownership.retire(uri(it)) } // the follow-up tickets retire returns are dropped
        repeat(FILES) { ownership.clearUnreferencedStateForTesting(uri(it)) } // as the collector would

        assertEquals(0, ownership.trackedFileCount, "a retirement with nothing outstanding must not be remembered")
    }

    @Test
    fun `only files with a live ticket keep state`() {
        val held = ownership.begin(uri(0))
        repeat(FILES - 1) { ownership.begin(uri(it + 1)) } // taken, then dropped
        repeat(FILES - 1) { ownership.retire(uri(it + 1)) } // closes and watcher changes after the work finished
        repeat(FILES - 1) { ownership.clearUnreferencedStateForTesting(uri(it + 1)) } // as the collector would

        assertEquals(1, ownership.trackedFileCount, "only the file with a live ticket keeps state")
        assertTrue(ownership.isCurrent(held), "dropping other files' state must not disturb a live ticket")
    }

    @Test
    fun `a ticket stays stale across repeated retirements`() {
        val ticket = ownership.begin(uri(0))
        ownership.retire(uri(0))
        ownership.retire(uri(0))

        assertFalse(ownership.isCurrent(ticket))
        assertFalse(ownership.commit(ticket) {})
    }

    @Test
    fun `an older buffer version is still refused after the newer one's state was collected`() {
        // #1383's guard must survive collection: a ticket taken later can carry a snapshot captured earlier
        val lifecycle = Any()
        run {
            val newer = ownership.acquire(uri(0), BufferOrder(lifecycle, 2) { true }) {}.first
            assertTrue(ownership.commit(newer) {})
        } // the newer compile is finished and unreferenced
        ownership.clearUnreferencedStateForTesting(uri(0))

        val older = ownership.acquire(uri(0), BufferOrder(lifecycle, 1) { false }) {}.first

        assertFalse(ownership.commit(older) {}, "version 1, no longer current, must not overwrite version 2")
    }

    @Test
    fun `garbage collection releases state once its tickets are unreachable (best effort)`() {
        repeat(FILES) { ownership.begin(uri(it)) }
        val released = (1..GC_ATTEMPTS).any {
            System.gc()
            Thread.sleep(GC_PAUSE_MILLIS)
            ownership.begin(URI.create("file:///Probe.groovy"))
            ownership.trackedFileCount <= 1
        }
        assumeTrue(released, "the JVM did not collect within $GC_ATTEMPTS attempts; the deterministic tests cover this")
    }

    private companion object {
        const val FILES = 200
        const val GC_ATTEMPTS = 10
        const val GC_PAUSE_MILLIS = 20L
    }
}
