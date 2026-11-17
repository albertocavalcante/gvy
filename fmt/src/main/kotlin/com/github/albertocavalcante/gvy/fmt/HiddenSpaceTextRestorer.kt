// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.openrewrite.SourceFile
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Space
import java.util.UUID

/**
 * Puts back source text that `AutoFormat` dropped from a [Space] (#1609).
 *
 * rewrite-groovy has no node for unary minus, unary plus, `~` or the spread-map operator, and keeps their
 * text (also the `+23` of `3.303e+23`) inside a whitespace [Space]. The tree still prints the original, but
 * `AutoFormat` treats that text as layout and discards it. Before formatting, [recording] notes the exact text of
 * every [Space] whose whitespace holds something other than whitespace. After formatting, [restore] puts the
 * text back in the slot it came from when that slot's whitespace holds no text any more, and keeps the
 * whitespace the formatter chose around it.
 *
 * A slot is the tree node that owns the [Space], the [Space.Location] and the position among the owner's
 * slots of that location. A slot that cannot be matched after formatting is left alone, and so is a slot that
 * still holds any text. Only text recorded from the input is ever written. If a formatter pass moved the text
 * to another slot and this one was emptied, the text could appear twice; [TokenStreamGuard], which this class
 * does not consult, then refuses the result.
 *
 * TODO(#1609): drop this class once rewrite-groovy parses these operators into nodes or keeps their text.
 *   See: https://github.com/albertocavalcante/gvy/issues/1609
 */
internal class HiddenSpaceTextRestorer private constructor(private val hidden: Map<SlotKey, String>) {

    /** Returns [formatted] with the recorded text put back where the formatter dropped it. */
    fun restore(formatted: SourceFile): SourceFile {
        if (hidden.isEmpty()) return formatted
        val restorer = object : SlotVisitor() {
            override fun onSpace(slot: SlotKey, space: Space): Space {
                val original = hidden[slot] ?: return space
                if (space.whitespace.holdsText()) return space
                return space.withWhitespace(withText(original, space.whitespace))
            }
        }
        return restorer.visit(formatted, Unit) as SourceFile
    }

    internal data class SlotKey(val owner: UUID, val location: Space.Location, val ordinal: Int)

    /** Numbers the slots of each owner and location in visiting order, the same before and after formatting. */
    private abstract class SlotVisitor : GroovyIsoVisitor<Unit>() {
        private val counts = mutableMapOf<Pair<UUID, Space.Location>, Int>()

        final override fun visitSpace(space: Space?, loc: Space.Location, p: Unit): Space {
            val visited = super.visitSpace(space, loc, p)
            val owner = cursor.firstEnclosing(J::class.java)?.id ?: return visited
            val ordinal = counts.getOrDefault(owner to loc, 0)
            counts[owner to loc] = ordinal + 1
            return onSpace(SlotKey(owner, loc, ordinal), visited)
        }

        abstract fun onSpace(slot: SlotKey, space: Space): Space
    }

    companion object {
        /** Notes the hidden text of every slot of [sources], before they are formatted. */
        fun recording(sources: List<SourceFile>): HiddenSpaceTextRestorer {
            val hidden = mutableMapOf<SlotKey, String>()
            val recorder = object : SlotVisitor() {
                override fun onSpace(slot: SlotKey, space: Space): Space {
                    if (space.whitespace.holdsText()) hidden[slot] = space.whitespace
                    return space
                }
            }
            sources.forEach { recorder.visit(it, Unit) }
            return HiddenSpaceTextRestorer(hidden)
        }

        private fun String.holdsText(): Boolean = any { !it.isWhitespace() }

        /**
         * The formatter's [formatted] whitespace followed by the text of [original] (its surrounding whitespace
         * trimmed off) and its trailing whitespace. An empty [formatted] keeps the original leading whitespace,
         * so the text does not fuse with the token before it.
         */
        private fun withText(original: String, formatted: String): String {
            val leading = original.takeWhile { it.isWhitespace() }
            val rest = original.substring(leading.length)
            val text = rest.trimEnd()
            return formatted.ifEmpty { leading } + text + rest.substring(text.length)
        }
    }
}
