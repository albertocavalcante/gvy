// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.AstViewModel
import com.github.albertocavalcante.gvy.viz.desktop.state.AwtDispatcher
import com.github.albertocavalcante.gvy.viz.desktop.state.DesktopFileOperations
import com.github.albertocavalcante.gvy.viz.desktop.state.FileEnvironment
import com.github.albertocavalcante.gvy.viz.desktop.state.InMemoryPreferences
import com.github.albertocavalcante.gvy.viz.desktop.state.ParseOutput
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AstVisualizerAppInteractionTest {
    private class Dialogs(private val uiThread: Thread) : DesktopFileDialogs {
        var openResult: File? = null
        var saveResult: File? = null
        var onSave: () -> Unit = {}
        val opened = mutableListOf<String>()
        val saved = mutableListOf<String>()

        @Volatile var allOnUiThread = true
        val uiDispatches = AtomicInteger()

        @Volatile var allDispatchesOnUiThread = true

        fun recordDispatch() {
            allDispatchesOnUiThread = allDispatchesOnUiThread && Thread.currentThread() === uiThread
            uiDispatches.incrementAndGet()
        }

        override fun openFile(initialDirectory: String): File? {
            allOnUiThread = allOnUiThread && Thread.currentThread() === uiThread
            opened.add(initialDirectory)
            return openResult
        }

        override fun saveFile(suggestedFileName: String): File? {
            allOnUiThread = allOnUiThread && Thread.currentThread() === uiThread
            saved.add(suggestedFileName)
            onSave()
            return saveResult
        }
    }

    private class RecordingUiDispatcher(private val delegate: CoroutineDispatcher, private val dialogs: Dialogs) :
        CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                dialogs.recordDispatch()
                block.run()
            }
        }
    }

    private class Files : DesktopFileOperations {
        var contents = "class Original {}"

        @Volatile var readFailure: String? = null

        @Volatile var writeFailure: String? = null
        val reads = Collections.synchronizedList(mutableListOf<File>())
        val writes = Collections.synchronizedList(mutableListOf<Pair<File, String>>())

        override suspend fun read(file: File): String {
            reads.add(file)
            readFailure?.let { error(it) }
            return contents
        }

        override suspend fun write(file: File, contents: String) {
            writeFailure?.let { error(it) }
            writes.add(file to contents)
        }
    }

    private fun model(files: DesktopFileOperations, prefs: InMemoryPreferences, uiDispatcher: CoroutineDispatcher) =
        AstViewModel(
            uiDispatcher,
            Dispatchers.IO,
            { input, _ ->
                ParseOutput(
                    tree = CoreAstNodeDto(input.source, "test", null, emptyList(), emptyMap()),
                    errors = emptyList(),
                )
            },
            0,
            FileEnvironment(Dispatchers.IO, files, prefs),
        )

    @Composable
    private fun testApp(model: AstViewModel, dialogs: Dialogs) {
        MaterialTheme { Box(Modifier.size(1400.dp, 900.dp)) { AstVisualizerApp(model, dialogs) } }
    }

    // Compose Desktop 1.10 routes OnClick semantics through the AWT UI thread. This is not a physical mouse test.

    @Test
    fun `browse cancel is neutral then accepted file loads and failed read is visible`() = runComposeUiTest {
        val files = Files()
        val prefs = InMemoryPreferences()
        val dialogs = Dialogs(runOnUiThread { Thread.currentThread() })
        val model = model(files, prefs, RecordingUiDispatcher(AwtDispatcher, dialogs))
        setContent { testApp(model, dialogs) }
        runOnIdle {}

        onNodeWithContentDescription("Browse").performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle {
            assertEquals("", model.sourceCode)
            assertTrue(files.reads.isEmpty())
            assertEquals(listOf(model.lastDirectory), dialogs.opened)
        }
        val selected = File("selected/source.groovy").absoluteFile
        runOnIdle { dialogs.openResult = selected }
        onNodeWithContentDescription("Browse").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) { !model.isParsing && model.sourceCode == files.contents }
        runOnIdle {
            assertEquals(listOf(selected), files.reads.toList())
            assertEquals(selected.parent, model.lastDirectory)
        }
        runOnIdle {
            files.readFailure = "read denied"
            dialogs.openResult = File("failed.groovy").absoluteFile
        }
        onNodeWithContentDescription("Browse").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) { !model.isParsing && model.parseErrors.isNotEmpty() }
        onNodeWithText("Failed to load file: read denied", substring = true).assertExists()
        assertTrue(dialogs.allOnUiThread)
        assertTrue(dialogs.uiDispatches.get() > 0)
        assertTrue(dialogs.allDispatchesOnUiThread)
    }

    @Test
    fun `JSON chooser cancellation is neutral and accepted export keeps captured snapshot`() = runComposeUiTest {
        val files = Files()
        val dialogs = Dialogs(runOnUiThread { Thread.currentThread() })
        val prefs = InMemoryPreferences()
        val model = model(files, prefs, RecordingUiDispatcher(AwtDispatcher, dialogs))
        setContent { testApp(model, dialogs) }
        runOnIdle {}
        runOnIdle {
            model.updateSourceCode("original")
            model.parseCode()
        }
        waitUntil(timeoutMillis = 10_000) { !model.isParsing && model.astTree != null }
        onNodeWithText("Export").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle {
            assertFalse(model.isExporting)
            assertTrue(files.writes.isEmpty())
        }
        runOnIdle {
            dialogs.saveResult = File("result.json").absoluteFile
            dialogs.onSave = { model.updateSourceCode("newer") }
        }
        onNodeWithText("Export").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) { files.writes.isNotEmpty() && !model.isExporting }
        val (file, contents) = files.writes.single()
        assertEquals("result.json", file.name)
        assertTrue(contents.contains("original"))
        assertFalse(contents.contains("newer"))
        onNodeWithText("Saved result.json").assertExists()
        waitUntil(timeoutMillis = 10_000) { !model.isParsing && model.astTree != null }
        runOnIdle {
            files.writeFailure = "disk full"
            dialogs.saveResult = File("broken.json").absoluteFile
            dialogs.onSave = {}
        }
        onNodeWithText("Export").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) {
            !model.isExporting && model.operationMessage?.contains("disk full") == true
        }
        onNodeWithText("Failed to save broken.json: disk full").assertExists()
        assertEquals(listOf("ast.json", "ast.json", "ast.json"), dialogs.saved)
        assertTrue(dialogs.allOnUiThread)
        assertTrue(dialogs.uiDispatches.get() > 0)
        assertTrue(dialogs.allDispatchesOnUiThread)
    }

    @Test
    fun `DOT export succeeds and write failures are shown`() = runComposeUiTest {
        val files = Files()
        val dialogs = Dialogs(runOnUiThread { Thread.currentThread() })
        val prefs = InMemoryPreferences()
        val model = model(files, prefs, RecordingUiDispatcher(AwtDispatcher, dialogs))
        setContent { testApp(model, dialogs) }
        runOnIdle {}
        runOnIdle {
            model.updateSourceCode("class Original {}")
            model.parseCode()
        }
        waitUntil(timeoutMillis = 10_000) { !model.isParsing && model.astTree != null }
        onNodeWithText("Export").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("DOT (Core)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle {
            assertFalse(model.isExporting)
            assertTrue(files.writes.isEmpty())
            dialogs.saveResult = File("result.dot").absoluteFile
        }
        onNodeWithText("Export").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("DOT (Core)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) { files.writes.isNotEmpty() && !model.isExporting }
        assertTrue(files.writes.single().second.contains("digraph"))
        onNodeWithText("Saved result.dot").assertExists()
        runOnIdle {
            files.writeFailure = "disk full"
            dialogs.saveResult = File("broken.dot").absoluteFile
        }
        onNodeWithText("Export").performSemanticsAction(SemanticsActions.OnClick) { it() }
        onNodeWithText("DOT (Core)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) {
            !model.isExporting && model.operationMessage?.contains("disk full") == true
        }
        onNodeWithText("Failed to save broken.dot: disk full").assertExists()
        assertEquals(listOf("ast.dot", "ast.dot", "ast.dot"), dialogs.saved)
        assertTrue(dialogs.allOnUiThread)
        assertTrue(dialogs.uiDispatches.get() > 0)
        assertTrue(dialogs.allDispatchesOnUiThread)
    }

    @Test
    fun `removing root cancels pending load`() = runComposeUiTest {
        val gate = CompletableDeferred<String>()
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val files = object : DesktopFileOperations {
            override suspend fun read(file: File): String {
                started.set(true)
                try {
                    return gate.await()
                } finally {
                    cancelled.set(true)
                }
            }
            override suspend fun write(file: File, contents: String) = Unit
        }
        val dialogs = Dialogs(runOnUiThread { Thread.currentThread() }).apply {
            openResult = File("slow.groovy").absoluteFile
        }
        val prefs = InMemoryPreferences()
        val model = model(files, prefs, RecordingUiDispatcher(AwtDispatcher, dialogs))
        val visible = mutableStateOf(true)
        setContent {
            if (visible.value) testApp(model, dialogs)
        }
        runOnIdle {}
        onNodeWithContentDescription("Browse").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitUntil(timeoutMillis = 10_000) { started.get() }
        runOnIdle { visible.value = false }
        waitUntil(timeoutMillis = 10_000) { cancelled.get() }
        assertEquals("", model.sourceCode)
    }
}
