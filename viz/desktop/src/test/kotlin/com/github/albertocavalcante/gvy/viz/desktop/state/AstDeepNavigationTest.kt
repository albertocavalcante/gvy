package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertSame

class AstDeepNavigationTest {
    private fun withDeepTree(action: (AstViewModel, AstNodeDto) -> Unit) {
        val leaf = CoreAstNodeDto("leaf", "leaf", RangeDto(1, 1, 1, 2), emptyList(), emptyMap())
        var root: AstNodeDto = leaf
        repeat(10_000) { depth ->
            root = CoreAstNodeDto("branch$depth", "branch", leaf.range, listOf(root), emptyMap())
        }
        val model = AstViewModel(
            Dispatchers.Unconfined,
            Dispatchers.Unconfined,
            { _, _ -> ParseOutput(root, emptyList()) },
            3_600_000,
            FileEnvironment(Dispatchers.Unconfined, prefs = InMemoryPreferences()),
        )
        try {
            model.updateSourceCode("x")
            model.parseCode()
            action(model, leaf)
        } finally {
            model.close()
        }
    }

    @Test
    fun `selecting an owned deeply nested DTO does not overflow the stack`() = withDeepTree { model, leaf ->
        model.selectNode(leaf)
        assertSame(leaf, model.selectedNode)
    }

    @Test
    fun `caret lookup prefers the deepest equal span without overflowing the stack`() = withDeepTree { model, leaf ->
        model.selectNodeAt(1, 1)
        assertSame(leaf, model.selectedNode)
    }
}
