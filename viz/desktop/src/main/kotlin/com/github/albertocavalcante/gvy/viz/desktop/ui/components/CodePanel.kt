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
            val start = getOffset(textFieldValue.text, range.startLine, range.startColumn)
            val end = getOffset(textFieldValue.text, range.endLine, range.endColumn)
            if (start != -1 && end != -1) {
                val targetSelection = TextRange(start, end)
                if (textFieldValue.selection != targetSelection) {
                    textFieldValue = textFieldValue.copy(selection = targetSelection)
                }
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
    val nonTrailingLines = lines.dropLastWhile { it.isEmpty() }
    val lineCount = nonTrailingLines.takeIf { it.isNotEmpty() }?.size?.coerceAtLeast(1) ?: 1
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

        val lines = text.text.lines()
        val builder = AnnotatedString.Builder(text)

        for (error in errors) {
            if (error.startLine != -1) {
                val start = getOffset(text.text, error.startLine, error.startColumn)
                val end = if (error.endLine != -1 && error.endColumn != -1) {
                    getOffset(text.text, error.endLine, error.endColumn)
                } else if (start != -1) {
                    // If end info is missing, highlight to the end of the start line.
                    val lineStartOffset = getOffset(text.text, error.startLine, 1)
                    val lineLength = lines.getOrNull(error.startLine - 1)?.length ?: 0
                    if (lineStartOffset != -1) {
                        lineStartOffset + lineLength
                    } else {
                        start + 1
                    }
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
    if (line < 1) return -1

    var currentLine = 1
    var index = 0
    var lineStart = 0

    // Advance through the text until we reach the start of the requested line,
    // correctly handling '\n', '\r', and '\r\n' line endings.
    while (index < text.length && currentLine < line) {
        val ch = text[index]
        if (ch == '\n') {
            currentLine++
            index++
            lineStart = index
        } else if (ch == '\r') {
            currentLine++
            index++
            // Handle CRLF as a single line break
            if (index < text.length && text[index] == '\n') {
                index++
            }
            lineStart = index
        } else {
            index++
        }
    }

    // If we exited the loop without reaching the requested line, the line does not exist.
    if (currentLine != line) return -1

    val col = column.coerceAtLeast(1)
    val offset = lineStart + col - 1
    return offset.coerceIn(0, text.length)
}

private fun getLineAndColumn(text: String, offset: Int): Pair<Int, Int> {
    val target = offset.coerceIn(0, text.length)
    var line = 1
    var column = 1
    var index = 0

    while (index < target) {
        val ch = text[index]
        if (ch == '\n') {
            line++
            column = 1
            index++
        } else if (ch == '\r') {
            line++
            column = 1
            index++
            if (index < target && text[index] == '\n') {
                index++
            }
        } else {
            column++
            index++
        }
    }

    return line to column
}
