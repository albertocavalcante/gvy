@file:Suppress("ktlint:standard:function-naming")

package com.github.albertocavalcante.gvy.viz.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

@Composable
fun DesktopPanel(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
