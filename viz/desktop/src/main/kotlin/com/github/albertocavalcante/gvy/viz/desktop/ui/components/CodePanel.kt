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
import com.github.albertocavalcante.gvy.viz.desktop.state.SourceCoordinates
import com.github.albertocavalcante.gvy.viz.desktop.state.SourceNavigation
import com.github.albertocavalcante.gvy.viz.desktop.state.errorSpans

/**
 * Panel displaying the source code in an editor-like style.
 */
@Composable
@Suppress("LongParameterList")
fun CodePanel(
    sourceCode: String,
    sourceNavigation: SourceNavigation?,
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
    LaunchedEffect(sourceNavigation) {
        sourceNavigation?.range?.let { range ->
            SourceCoordinates(textFieldValue.text).span(range)?.let { span ->
                val targetSelection = TextRange(span.first, span.last + 1)
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
                        val pos = SourceCoordinates(newValue.text).position(newValue.selection.start)
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

        val builder = AnnotatedString.Builder(text)

        for (span in errorSpans(text.text, errors)) {
            builder.addStyle(
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = Color.Red,
                ),
                span.first,
                span.last + 1,
            )
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
