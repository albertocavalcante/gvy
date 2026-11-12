// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class BuildFileChangeDebouncerTest {
    private val project = Path.of("project")

    @Test
    fun `a received change waits for the complete quiet period`(): Unit = runTest {
        val events = mutableListOf<Pair<Path, String>>()
        val changes = BuildFileChangeDebouncer(this, StandardTestDispatcher(testScheduler), 500) { path, name ->
            events.add(path to name)
        }
        changes.submit(project, "build.gradle")
        runCurrent()

        advanceTimeBy(499)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(project to "build.gradle"), events)
    }

    @Test
    fun `a burst produces one callback after the final received change`(): Unit = runTest {
        val events = mutableListOf<Pair<Path, String>>()
        val changes = BuildFileChangeDebouncer(this, StandardTestDispatcher(testScheduler), 500) { path, name ->
            events.add(path to name)
        }
        repeat(5) { change ->
            changes.submit(project, if (change == 4) "settings.gradle" else "build.gradle")
            runCurrent()
            advanceTimeBy(50)
        }

        advanceTimeBy(449)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(project to "settings.gradle"), events)
    }

    @Test
    fun `deliveries separated by a quiet period remain distinct changes`(): Unit = runTest {
        val events = mutableListOf<Pair<Path, String>>()
        val changes = BuildFileChangeDebouncer(this, StandardTestDispatcher(testScheduler), 500) { path, name ->
            events.add(path to name)
        }
        changes.submit(project, "build.gradle")
        advanceUntilIdle()
        changes.submit(project, "settings.gradle")
        advanceUntilIdle()

        assertEquals(listOf(project to "build.gradle", project to "settings.gradle"), events)
    }

    @Test
    fun `a new change cancels a callback queued exactly at its deadline`(): Unit = runTest {
        val events = mutableListOf<Pair<Path, String>>()
        val changes = BuildFileChangeDebouncer(this, StandardTestDispatcher(testScheduler), 500) { path, name ->
            events.add(path to name)
        }
        changes.submit(project, "build.gradle")
        runCurrent()
        advanceTimeBy(500)
        changes.submit(project, "settings.gradle")
        runCurrent()
        assertTrue(events.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf(project to "settings.gradle"), events)
    }

    @Test
    fun `cancelling a session suppresses its callbacks and preserves its caller`(): Unit = runTest {
        val events = mutableListOf<Pair<Path, String>>()
        val session = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val changes = BuildFileChangeDebouncer(session, StandardTestDispatcher(testScheduler), 500) { path, name ->
            events.add(path to name)
        }
        changes.submit(project, "build.gradle")
        runCurrent()
        advanceTimeBy(500)

        session.cancel()
        changes.submit(project, "settings.gradle")
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertTrue(isActive)
    }

    @Test
    fun `zero delay still dispatches through the owned session`(): Unit = runTest {
        val events = mutableListOf<Pair<Path, String>>()
        val session = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val changes = BuildFileChangeDebouncer(session, StandardTestDispatcher(testScheduler), 0) { path, name ->
            events.add(path to name)
        }
        changes.submit(project, "build.gradle")
        assertTrue(events.isEmpty())

        session.cancel()
        runCurrent()

        assertTrue(events.isEmpty())
    }
}
