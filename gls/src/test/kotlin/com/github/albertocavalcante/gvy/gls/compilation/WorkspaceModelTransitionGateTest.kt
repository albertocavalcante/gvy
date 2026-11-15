// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceModelTransitionGateTest {
    @Test
    fun `snapshot waits until the model transition finishes`(): Unit = runTest {
        val gate = WorkspaceModelTransitionGate()
        val model = WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "test" }
        val transitionStarted = CompletableDeferred<Unit>()
        val releaseTransition = CountDownLatch(1)
        val transition = async(Dispatchers.IO) {
            gate.transition {
                transitionStarted.complete(Unit)
                assertTrue(releaseTransition.await(5, TimeUnit.SECONDS))
            }
        }

        transitionStarted.await()
        val snapshot = async { gate.awaitSnapshot { model } }
        runCurrent()
        assertFalse(snapshot.isCompleted, "a compiler must not capture a partially updated model")

        releaseTransition.countDown()
        transition.await()
        assertSame(model, snapshot.await().model)
    }

    @Test
    fun `failed transition stays closed after a later no-op transition`(): Unit = runTest {
        val gate = WorkspaceModelTransitionGate()
        val model = WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "test" }

        assertFailsWith<IllegalArgumentException> {
            gate.transition { throw IllegalArgumentException("failed update") }
        }
        assertFailsWith<IllegalStateException> { gate.awaitSnapshot { model } }

        gate.transition { Unit }

        assertFailsWith<IllegalStateException> { gate.awaitSnapshot { model } }
    }

    @Test
    fun `no-op transition invalidates read permits`() {
        val gate = WorkspaceModelTransitionGate()
        val permit = checkNotNull(gate.readPermit())
        gate.transition(action = { false }, shouldAdvanceGeneration = { it })
        assertFalse(permit())
        assertTrue(checkNotNull(gate.readPermit())())
    }

    @Test
    fun `no-op transition does not invalidate a ready snapshot`(): Unit = runTest {
        val gate = WorkspaceModelTransitionGate()
        val model = WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "test" }
        val snapshot = gate.awaitSnapshot { model }
        val equivalent = WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "test" }

        gate.transition(action = { false }, shouldAdvanceGeneration = { it })

        assertTrue(gate.isCurrent(snapshot, { equivalent }, WorkspaceModel::hasSameWorkspaceCompileInputs))
        assertNull(
            gate.publishIfCurrent(snapshot, { equivalent }) { true },
            "Default publication remains identity-based",
        )
        assertTrue(
            gate.publishIfCurrent(snapshot, { equivalent }, WorkspaceModel::hasSameWorkspaceCompileInputs) { true } == true,
        )
        val changed = WorkspaceModel(null, listOf(Path.of("dependency.jar")), emptyList(), emptyList()) { _, _ -> "test" }
        assertNull(gate.publishIfCurrent(snapshot, { changed }, WorkspaceModel::hasSameWorkspaceCompileInputs) { true })
    }

    @Test
    fun `equivalent inputs cannot publish across an active transition or retired generation`(): Unit = runTest {
        val gate = WorkspaceModelTransitionGate()
        val model = WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "test" }
        val snapshot = gate.awaitSnapshot { model }
        val equivalent = WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "test" }
        var published = false

        gate.transition {
            assertNull(
                gate.publishIfCurrent(snapshot, { equivalent }, WorkspaceModel::hasSameWorkspaceCompileInputs) {
                    published = true
                },
            )
        }

        assertNull(
            gate.publishIfCurrent(snapshot, { equivalent }, WorkspaceModel::hasSameWorkspaceCompileInputs) {
                published = true
            },
        )
        assertFalse(published)
    }
}
