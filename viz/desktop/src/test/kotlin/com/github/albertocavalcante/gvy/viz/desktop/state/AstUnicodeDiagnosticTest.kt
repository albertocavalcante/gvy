package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AstUnicodeDiagnosticTest {
    @Test
    fun `native diagnostic coordinates are converted from zero based on ASCII source`() = runTest {
        val source = "def value = )"
        val diagnostic = GroovyParserFacade().parse(
            ParseRequest(uri = URI("file:///test.groovy"), content = source),
        ).diagnostics.first()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel =
            AstViewModel(
                dispatcher,
                dispatcher,
                ::parseSnapshot,
                300,
                FileEnvironment(dispatcher, prefs = InMemoryPreferences()),
            )
        try {
            viewModel.setParser(ParserType.NATIVE)
            viewModel.updateSourceCode(source)
            viewModel.parseCode()
            advanceUntilIdle()

            val error = viewModel.parseErrors.first()
            assertTrue(error.startLine > 0)
            assertEquals(diagnostic.range.start.line + 1, error.startLine)
            assertEquals(diagnostic.range.start.character + 1, error.startColumn)
            assertEquals(diagnostic.range.end.line + 1, error.endLine)
            assertEquals(diagnostic.range.end.character + 1, error.endColumn)
        } finally {
            viewModel.close()
            runCurrent()
        }
    }

    @Test
    fun `native diagnostics after supplementary characters have no misleading location`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel =
            AstViewModel(
                dispatcher,
                dispatcher,
                ::parseSnapshot,
                300,
                FileEnvironment(dispatcher, prefs = InMemoryPreferences()),
            )
        try {
            viewModel.setParser(ParserType.NATIVE)
            viewModel.updateSourceCode("def text = '😀'; def =")
            viewModel.parseCode()
            advanceUntilIdle()

            assertTrue(viewModel.parseErrors.isNotEmpty())
            val error = viewModel.parseErrors.first()
            assertEquals(-1, error.startLine)
            assertEquals(-1, error.startColumn)
            assertEquals(-1, error.endLine)
            assertEquals(-1, error.endColumn)
        } finally {
            viewModel.close()
            runCurrent()
        }
    }
}
