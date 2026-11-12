// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ControlledIoDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    val queued: Int get() = tasks.size
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }
    fun runFirst() {
        tasks.removeFirst().run()
    }
    fun runLast() {
        tasks.removeLast().run()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AstFileOperationsTest {
    @Test
    fun `late file load cannot replace a newer edit`() = runTest {
        val gate = CompletableDeferred<String>()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = gate.await()
            override suspend fun write(file: File, contents: String) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.loadFile(File("slow.groovy"))
        runCurrent()
        model.updateSourceCode("newer edit")
        gate.complete("old file")
        advanceUntilIdle()
        assertEquals("newer edit", model.sourceCode)
        assertEquals("newer edit", model.astTree?.id)
        assertTrue(model.parseErrors.isEmpty())
        model.close()
        runCurrent()
    }

    @Test
    fun `late file failure does not replace newer parse or report stale error`() = runTest {
        val gate = CompletableDeferred<String>()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = gate.await()
            override suspend fun write(file: File, contents: String) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.loadFile(File("slow.groovy"))
        runCurrent()
        model.updateSourceCode("newer edit")
        gate.completeExceptionally(IllegalStateException("old failure"))
        advanceUntilIdle()
        assertEquals("newer edit", model.sourceCode)
        assertEquals("newer edit", model.astTree?.id)
        assertTrue(model.parseErrors.isEmpty())
        model.close()
        runCurrent()
    }

    @Test
    fun `failed current load clears prior tree and exposes its error`() = runTest {
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = error("read failed")
            override suspend fun write(file: File, contents: String) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.updateSourceCode("valid")
        advanceUntilIdle()
        model.selectNode(model.astTree)
        model.loadFile(File("broken.groovy"))
        advanceUntilIdle()
        assertNull(model.astTree)
        assertNull(model.selectedNode)
        assertTrue(model.parseErrors.single().message.contains("read failed"))
        model.close()
        runCurrent()
    }

    @Test
    fun `accepted export writes the captured tree even after editing and closing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val captured = mutableListOf<String>()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = error("unexpected read")
            override suspend fun write(file: File, contents: String) {
                gate.await()
                captured.add(contents)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.updateSourceCode("original")
        advanceUntilIdle()
        val snapshot = requireNotNull(model.exportSnapshot(ExportFormat.JSON))
        model.saveExport(snapshot, File("result.json"))
        runCurrent()
        model.updateSourceCode("newer")
        model.close()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue(captured.single().contains("original"))
        assertTrue(!captured.single().contains("newer"))
    }

    @Test
    fun `write failure is visible while the view remains open`() = runTest {
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = error("unexpected read")
            override suspend fun write(file: File, contents: String) = error("disk full")
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.updateSourceCode("original")
        advanceUntilIdle()
        model.saveExport(requireNotNull(model.exportSnapshot(ExportFormat.JSON)), File("result.json"))
        advanceUntilIdle()
        assertTrue(model.operationMessage?.contains("disk full") == true)
        assertTrue(model.canClose())
        model.close()
        runCurrent()
    }

    @Test
    fun `window close waits for accepted save and becomes available after completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = error("unexpected read")
            override suspend fun write(file: File, contents: String) {
                gate.await()
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.updateSourceCode("original")
        advanceUntilIdle()
        model.saveExport(requireNotNull(model.exportSnapshot(ExportFormat.JSON)), File("result.json"))
        runCurrent()
        assertFalse(model.canClose())
        assertTrue(model.operationMessage?.contains("finish before closing") == true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.canClose())
        model.close()
        runCurrent()
    }

    @Test
    fun `unexpected current read cancellation clears busy state and reports failure`() = runTest {
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = throw CancellationException("read cancelled")
            override suspend fun write(file: File, contents: String) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, InMemoryPreferences()))
        model.loadFile(File("current.groovy"))
        advanceUntilIdle()
        assertFalse(model.isParsing)
        assertTrue(model.parseErrors.single().message.contains("cancel"))
        model.close()
        runCurrent()
    }

    @Test
    fun `successful load stores its directory in injected preferences`() = runTest {
        val prefs = InMemoryPreferences()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = "contents"
            override suspend fun write(file: File, contents: String) = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(dispatcher, files, prefs))
        val file = File("folder/sample.groovy").absoluteFile
        try {
            model.loadFile(file)
            advanceUntilIdle()
            assertEquals(file.parent, model.lastDirectory)
            assertEquals(file.parent, prefs.get("lastDirectory", ""))
        } finally {
            model.close()
            runCurrent()
        }
    }

    @Test
    fun `remembered directories persist in load order even when IO dispatch is reordered`() = runTest {
        val prefs = InMemoryPreferences()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String = file.name
            override suspend fun write(file: File, contents: String) = Unit
        }
        val ui = StandardTestDispatcher(testScheduler)
        val io = ControlledIoDispatcher()
        val model = AstViewModel(ui, ui, { input, _ ->
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 300, FileEnvironment(io, files, prefs))
        val first = File("first/A.groovy").absoluteFile
        val second = File("second/B.groovy").absoluteFile
        try {
            model.loadFile(first)
            runCurrent()
            io.runFirst() // complete first read
            runCurrent() // first preference write is queued
            model.loadFile(second)
            runCurrent()
            io.runLast() // complete second read before first preference write
            runCurrent()
            assertEquals(1, io.queued) // second preference write waits behind the first
            io.runFirst()
            runCurrent()
            io.runFirst()
            runCurrent()
            assertEquals(second.parent, prefs.get("lastDirectory", ""))
        } finally {
            model.close()
            runCurrent()
        }
    }
}
