// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import java.util.Collections
import java.util.IdentityHashMap

/** Pure navigation policy for one immutable source/tree snapshot; DTO instances are never copied. */
internal class AstNavigation(root: AstNodeDto, source: String) {
    private data class Visit(val node: AstNodeDto, val depth: Int)
    private data class Span(val node: AstNodeDto, val start: Int, val end: Int, val depth: Int) {
        private val length: Int get() = end - start

        fun isMoreSpecificThan(previous: Span): Boolean =
            length < previous.length || length == previous.length && depth > previous.depth
    }

    private val coordinates = SourceCoordinates(source)
    private val members = Collections.newSetFromMap(IdentityHashMap<AstNodeDto, Boolean>())
    private val spans = buildList {
        val pending = ArrayDeque<Visit>()
        pending.addLast(Visit(root, 0))
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            members.add(node)
            node.range?.let(coordinates::span)?.let { range ->
                add(Span(node, range.first, range.last + 1, depth))
            }
            node.children.asReversed().forEach { pending.addLast(Visit(it, depth + 1)) }
        }
    }

    fun contains(node: AstNodeDto): Boolean = members.contains(node)

    fun nodeAt(line: Int, column: Int): AstNodeDto? {
        val cursor = coordinates.offset(line, column) ?: return null
        var best: Span? = null
        for (candidate in spans) {
            if (cursor < candidate.start || cursor >= candidate.end) continue
            val previous = best
            // Preorder wins equal-depth ties; a deeper or smaller containing span takes precedence.
            if (previous == null || candidate.isMoreSpecificThan(previous)) {
                best = candidate
            }
        }
        return best?.node
    }
}
