package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExportOrchestrationTest {
    private class Files : DesktopFileOperations {
        val writes = mutableListOf<String>()
        override suspend fun read(file: File): String = error("Unexpected read")
        override suspend fun write(file: File, contents: String) {
            writes.add(contents)
        }
    }

    @Test
    fun `DOT encoding excludes parser work until completion and keeps the captured source`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = AstViewModel(dispatcher, dispatcher, { input, _ ->
            events.add("parse:${input.source}")
            ParseOutput(CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()), emptyList())
        }, 0, FileEnvironment(dispatcher, files, InMemoryPreferences()), { snapshot ->
            events.add("encode:${snapshot.source}")
            gate.await()
            "DOT:${snapshot.source}"
        })
        try {
            model.updateSourceCode("A")
            advanceUntilIdle()
            model.saveExport(requireNotNull(model.exportSnapshot(ExportFormat.DOT)), File("result.dot"))
            runCurrent()
            model.updateSourceCode("B")
            runCurrent()
            assertEquals(listOf("parse:A", "encode:A"), events)
            assertFalse(model.canClose())
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("parse:A", "encode:A", "parse:B"), events)
            assertEquals(listOf("DOT:A"), files.writes)
            assertTrue(model.canClose())
        } finally {
            gate.complete(Unit)
            model.close()
            runCurrent()
        }
    }

    @Test
    fun `encoding failure is visible releases the export slot and never writes`() = runTest {
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var attempts = 0
        val model = AstViewModel(
            dispatcher,
            dispatcher,
            { _, _ -> ParseOutput(null, emptyList()) },
            0,
            FileEnvironment(dispatcher, files, InMemoryPreferences()),
            {
                attempts++
                error("encoder failed")
            },
        )
        try {
            val snapshot = requireNotNull(model.exportSnapshot(ExportFormat.DOT))
            repeat(2) {
                model.saveExport(snapshot, File("result.dot"))
                advanceUntilIdle()
                assertTrue(model.operationMessage?.contains("encoder failed") == true)
                assertFalse(model.isExporting)
                assertTrue(model.canClose())
            }
            assertEquals(2, attempts)
            assertTrue(files.writes.isEmpty())
        } finally {
            model.close()
            runCurrent()
        }
    }
}
