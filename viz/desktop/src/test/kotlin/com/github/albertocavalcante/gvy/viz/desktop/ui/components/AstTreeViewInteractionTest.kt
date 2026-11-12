// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AstTreeViewInteractionTest {
    private fun node(id: String, vararg children: AstNodeDto): AstNodeDto =
        CoreAstNodeDto(id, "ClassNode", null, children.toList(), mapOf("name" to id))

    @Test
    fun `cursor selection reveals a descendant under a collapsed root`() = runComposeUiTest {
        val target = node("needle")
        val root = node("root", node("branch", node("method", target)))
        val selected = mutableStateOf<AstNodeDto?>(null)
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, selected.value, { selected.value = it }, Modifier.fillMaxSize())
                }
            }
        }
        onAllNodesWithContentDescription("Collapse").onFirst().performClick()
        runOnIdle { selected.value = target }
        onNode(hasText("needle") and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun `filter reveals matching paths and clearing it restores manual collapse`() = runComposeUiTest {
        val root = node("root", node("branch", node("method", node("needle"))))
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, null, {}, Modifier.fillMaxSize())
                }
            }
        }
        onAllNodesWithContentDescription("Collapse").onFirst().performClick()
        val filter = onNode(hasSetTextAction())
        filter.performTextInput("needle")
        onNode(hasText("needle") and !hasSetTextAction()).assertIsDisplayed()
        filter.performTextClearance()
        onNodeWithText("branch").assertDoesNotExist()
        filter.performTextInput("missing")
        onNodeWithText("No match.").assertIsDisplayed()
    }

    @Test
    fun `filtered row clicks preserve the original node instance`() = runComposeUiTest {
        val target = node("needle")
        val root = node("root", target, node("other"))
        var clicked: AstNodeDto? = null
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, null, { clicked = it }, Modifier.fillMaxSize())
                }
            }
        }
        onNode(hasSetTextAction()).performTextInput("needle")
        onNode(hasText("needle") and !hasSetTextAction()).performClick()
        runOnIdle { assertSame(target, clicked) }
    }

    @Test
    fun `large expanded trees compose a viewport of rows and reveal offscreen selections`() = runComposeUiTest {
        val children = (0 until 1_000).map { node("name-$it") }
        val root = node("root", *children.toTypedArray())
        val selected = mutableStateOf<AstNodeDto?>(null)
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, selected.value, { selected.value = it }, Modifier.fillMaxSize())
                }
            }
        }
        val composed = onAllNodesWithText("ClassNode", useUnmergedTree = true).fetchSemanticsNodes().size
        println("Tree fixture: 1001 nodes, 600x420dp viewport, $composed composed type labels")
        assertTrue(composed < 100, "Expected a viewport of rows, found $composed composed labels")
        runOnIdle { selected.value = children.last() }
        onNodeWithText("name-999").assertIsDisplayed()
    }

    @Test
    fun `source selection outside the filter reveals its own path`() = runComposeUiTest {
        val target = node("needle")
        val root = node("root", node("branch", target), node("other"))
        val selected = mutableStateOf<AstNodeDto?>(null)
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, selected.value, { selected.value = it }, Modifier.fillMaxSize())
                }
            }
        }
        onNode(hasSetTextAction()).performTextInput("other")
        runOnIdle { selected.value = target }
        onNode(hasText("needle") and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun `equal reparsed DTOs reset expansion and keep the new node identity`() = runComposeUiTest {
        val firstTarget = node("needle")
        val tree = mutableStateOf<AstNodeDto?>(node("root", firstTarget))
        val selected = mutableStateOf<AstNodeDto?>(null)
        var clicked: AstNodeDto? = null
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(tree.value, selected.value, { clicked = it }, Modifier.fillMaxSize())
                }
            }
        }
        onAllNodesWithContentDescription("Collapse").onFirst().performClick()
        val newTarget = node("needle")
        runOnIdle {
            tree.value = null
            tree.value = node("root", newTarget)
            selected.value = newTarget
        }
        onNode(hasText("needle") and !hasSetTextAction()).assertIsDisplayed().performClick()
        runOnIdle { assertSame(newTarget, clicked) }
    }

    @Test
    fun `clicking a filtered result preserves unfiltered manual folds`() = runComposeUiTest {
        val target = node("needle")
        val root = node("root", node("branch", target))
        val selected = mutableStateOf<AstNodeDto?>(null)
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, selected.value, { selected.value = it }, Modifier.fillMaxSize())
                }
            }
        }
        onAllNodesWithContentDescription("Collapse").onFirst().performClick()
        val filter = onNode(hasSetTextAction())
        filter.performTextInput("needle")
        onNode(hasText("needle") and !hasSetTextAction()).performClick()
        runOnIdle { assertSame(target, selected.value) }
        filter.performTextClearance()
        onNodeWithText("branch").assertDoesNotExist()
    }

    @Test
    fun `visible selection and later manual scrolling or folding do not jump the viewport`() = runComposeUiTest {
        val branches = (0 until 200).map { node("branch-$it", node("child-$it")) }
        val root = node("root", *branches.toTypedArray())
        val selected = mutableStateOf<AstNodeDto?>(null)
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 420.dp)) {
                    AstTreeView(root, selected.value, { selected.value = it }, Modifier.fillMaxSize())
                }
            }
        }
        val list = onNode(hasScrollToIndexAction())
        list.performScrollToIndex(250)
        onNodeWithText("branch-125").performClick()
        onNodeWithText("child-124").assertIsDisplayed()
        list.performScrollToIndex(300)
        onAllNodesWithContentDescription("Collapse").onFirst().performClick()
        runOnIdle { assertSame(branches[125], selected.value) }
        onNodeWithText("branch-150").assertIsDisplayed()
        onNodeWithText("child-149").assertIsDisplayed()
    }
}
