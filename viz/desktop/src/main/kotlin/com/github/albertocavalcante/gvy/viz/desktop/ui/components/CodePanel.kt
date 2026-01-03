@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.desktop.state.CodeError
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto

/**
 * Panel displaying the source code.
 */
@Composable
@Suppress("LongParameterList")
fun CodePanel(
    sourceCode: String,
    selectedNode: AstNodeDto?,
    errors: List<CodeError>,
    onCodeChange: (String) -> Unit,
    onCursorChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var textFieldValue by remember(sourceCode) {
        mutableStateOf(TextFieldValue(text = sourceCode))
    }

    // Sync external selection (from tree) to editor
    LaunchedEffect(selectedNode) {
        selectedNode?.range?.let { range ->
            val start = getOffset(sourceCode, range.startLine, range.startColumn)
            val end = getOffset(sourceCode, range.endLine, range.endColumn)
            if (start != -1 && end != -1) {
                textFieldValue = textFieldValue.copy(selection = TextRange(start, end))
            }
        }
    }

    Box(modifier = modifier.padding(8.dp)) {
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val codeChanged = newValue.text != textFieldValue.text
                val selectionChanged = newValue.selection != textFieldValue.selection

                textFieldValue = newValue

                if (codeChanged) {
                    onCodeChange(newValue.text)
                }

                if (selectionChanged && !codeChanged) {
                    val pos = getLineAndColumn(newValue.text, newValue.selection.start)
                    onCursorChange(pos.first, pos.second)
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = { Text("Groovy Source Code") },
            placeholder = { Text("Paste Groovy code here or load a file...") },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            visualTransformation = remember(errors) { ErrorVisualTransformation(errors) },
        )
    }
}

private class ErrorVisualTransformation(private val errors: List<CodeError>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (errors.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(text)

        for (error in errors) {
            if (error.startLine != -1) {
                val start = getOffset(text.text, error.startLine, error.startColumn)
                // Use a default length if end is missing or same as start
                val end = if (error.endLine != -1) {
                    getOffset(text.text, error.endLine, error.endColumn)
                } else {
                    start + 1
                }

                if (start != -1 && end != -1 && end > start) {
                    builder.addStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = Color.Red,
                        ),
                        start,
                        end,
                    )
                }
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private fun getOffset(text: String, line: Int, column: Int): Int {
    val lines = text.lines()
    if (line > lines.size || line < 1) return -1
    var offset = 0
    for (i in 0 until line - 1) {
        offset += lines[i].length + 1 // +1 for newline
    }
    val col = column.coerceAtLeast(1)
    return (offset + col - 1).coerceIn(0, text.length)
}

private fun getLineAndColumn(text: String, offset: Int): Pair<Int, Int> {
    if (offset < 0 || offset > text.length) return 1 to 1
    val prefix = text.take(offset)
    val lines = prefix.lines()
    val line = lines.size
    val column = lines.last().length + 1
    return line to column
}
