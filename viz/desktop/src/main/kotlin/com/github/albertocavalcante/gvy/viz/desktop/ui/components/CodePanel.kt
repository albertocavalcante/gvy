@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.albertocavalcante.gvy.viz.desktop.state.CodeError
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto

/**
 * Panel displaying the source code in an editor-like style.
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
    // FIX: Do NOT key remember on sourceCode, to avoid resetting selection on typing.
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = sourceCode))
    }

    // Handle external source code changes (e.g. file load)
    LaunchedEffect(sourceCode) {
        if (textFieldValue.text != sourceCode) {
            textFieldValue = TextFieldValue(text = sourceCode) // Reset selection to start
        }
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

    Row(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        // Gutter (Line numbers)
        Gutter(sourceCode)

        // Editor
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            BasicTextField(
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
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = remember(errors) { ErrorVisualTransformation(errors) },
            )

            if (sourceCode.isEmpty()) {
                Text(
                    "Paste Groovy code here...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun Gutter(code: String) {
    val lines = code.lines()
    val lineCount = lines.size.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        for (i in 1..lineCount) {
            Text(
                text = i.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

private class ErrorVisualTransformation(private val errors: List<CodeError>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (errors.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(text)

        for (error in errors) {
            if (error.startLine != -1) {
                val start = getOffset(text.text, error.startLine, error.startColumn)
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
        offset += lines[i].length + 1
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
