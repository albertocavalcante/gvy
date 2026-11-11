package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import com.github.albertocavalcante.gvy.viz.desktop.state.AstViewModel
import com.github.albertocavalcante.gvy.viz.desktop.state.InMemoryPreferences
import kotlin.test.Test
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class CodePanelInteractionTest {
    @Test
    fun `caret and selection navigation preserve edits while tree clicks select source`() = runComposeUiTest {
        val model = AstViewModel(InMemoryPreferences())
        model.updateSourceCode("def x = 1")
        model.parseCode()
        setContent {
            MaterialTheme {
                CodePanel(
                    sourceCode = model.sourceCode,
                    sourceNavigation = model.sourceNavigation,
                    errors = model.parseErrors,
                    onCodeChange = {
                        model.updateSourceCode(it)
                        model.parseCode()
                    },
                    onCursorChange = model::selectNodeAt,
                )
            }
        }
        waitUntil(timeoutMillis = 10_000) { !model.isParsing }
        val editor = onNode(hasSetTextAction())
        editor.performClick()
        editor.performTextInputSelection(TextRange(4))
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4)))
        editor.performKeyInput { pressKey(Key.DirectionRight) }
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(5)))
        editor.performTextInput("Q")
        waitUntil(timeoutMillis = 10_000) { !model.isParsing }
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(6)))
        runOnIdle { kotlin.test.assertEquals("def xQ = 1", model.sourceCode) }
        editor.performTextInputSelection(TextRange(4, 6))
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4, 6)))
        editor.performTextInput("z")
        waitUntil(timeoutMillis = 10_000) { !model.isParsing }
        runOnIdle { kotlin.test.assertEquals("def z = 1", model.sourceCode) }
        lateinit var treeNode: com.github.albertocavalcante.gvy.viz.model.AstNodeDto
        runOnIdle {
            model.selectNodeAt(1, 5)
            treeNode = assertNotNull(model.selectedNode)
            model.selectNode(treeNode)
        }
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4, 5)))
        editor.performTextInputSelection(TextRange(0))
        runOnIdle { model.selectNode(treeNode) }
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4, 5)))
        runOnIdle { model.close() }
    }
}
