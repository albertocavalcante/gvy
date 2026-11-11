package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.AstViewModel
import com.github.albertocavalcante.gvy.viz.desktop.state.ExportFormat
import com.github.albertocavalcante.gvy.viz.desktop.state.InMemoryPreferences
import com.github.albertocavalcante.gvy.viz.desktop.state.ParserType
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class EditorTreeNavigationTest {
    @Test
    fun `Core filtered tree selection navigates and replaces the exact source span`() =
        verifyNavigation(ParserType.CORE)

    @Test
    fun `Native filtered tree selection navigates and replaces the exact source span`() =
        verifyNavigation(ParserType.NATIVE)

    @Test
    fun `Rewrite filtered tree selection navigates and replaces the exact source span`() =
        verifyNavigation(ParserType.REWRITE)

    private fun find(root: AstNodeDto, predicate: (AstNodeDto) -> Boolean): AstNodeDto? {
        val pending = ArrayDeque<AstNodeDto>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) return node
            pending.addAll(node.children)
        }
        return null
    }

    private fun assertExportRange(output: File, target: AstNodeDto) {
        val exported = Json { classDiscriminator = "nodeClass" }
            .decodeFromString(AstNodeDto.serializer(), output.readText())
        assertEquals(target.range, assertNotNull(find(exported) { it.id == target.id }).range)
    }

    private fun verifyNavigation(parser: ParserType) = runComposeUiTest {
        val source = "class Needle { int value = 1 }\nclass Other {}"
        val end = source.indexOf('\n')
        val model = AstViewModel(InMemoryPreferences())
        val directory = Files.createTempDirectory("gvy-editor-test-")
        val output = directory.resolve("ast.json").toFile()
        lateinit var target: AstNodeDto

        setContent {
            MaterialTheme {
                Row(Modifier.size(1_000.dp, 500.dp)) {
                    AstTreeView(
                        model.astTree,
                        model.selectedNode,
                        model::selectNode,
                        Modifier.weight(1f).testTag("tree-panel"),
                    )
                    CodePanel(
                        model.sourceCode,
                        model.sourceNavigation,
                        model.parseErrors,
                        { model.updateSourceCode(it) },
                        model::selectNodeAt,
                        Modifier.weight(1f).testTag("source-panel"),
                    )
                }
            }
        }
        try {
            runOnIdle {
                model.setParser(parser)
                model.updateSourceCode(source)
                model.parseCode()
            }
            waitUntil(timeoutMillis = 30_000) { !model.isParsing && model.astTree != null }
            runOnIdle {
                val tree = assertNotNull(model.astTree)
                target = assertNotNull(find(tree) { it.properties["name"] == "Needle" })
                val snapshot = assertNotNull(model.exportSnapshot(ExportFormat.JSON))
                model.saveExport(snapshot, output)
            }
            waitUntil(timeoutMillis = 30_000) { !model.isExporting }
            assertExportRange(output, target)
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("tree-panel"))).performTextInput("Needle")
            onNode(hasText("Needle") and !hasSetTextAction()).performClick()
            runOnIdle {
                assertSame(target, model.selectedNode)
                assertEquals(RangeDto(1, 1, 1, end + 1), assertNotNull(model.sourceNavigation).range)
            }
            val editor = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("source-panel")))
            editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, end)))
            editor.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            editor.performTextInput("class Replacement {}")
            runOnIdle { assertEquals("class Replacement {}\nclass Other {}", model.sourceCode) }
        } finally {
            runOnIdle { model.close() }
            directory.toFile().deleteRecursively()
        }
    }
}
