package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import org.openrewrite.Cursor
import org.openrewrite.PrintOutputCapture
import org.openrewrite.Tree
import org.openrewrite.groovy.GroovyPrinter
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J
import org.openrewrite.marker.Marker
import org.openrewrite.marker.SearchResult
import java.util.UUID
import java.util.function.UnaryOperator

/**
 * Source spans indexed by original tree IDs. Lines and UTF-16 columns are 1-based; ends are exclusive.
 * The original parse tree is never modified.
 */
internal class RewriteSourcePositions private constructor(private val ranges: Map<UUID, Range>) {
    fun range(tree: Tree): Range? = ranges[tree.id]

    companion object {
        /**
         * The temporary markers are added to an immutable copy. Never use positions if the printer
         * does not reproduce the source: offsets from a different print cannot identify this input.
         */
        fun create(source: String, compilationUnit: G.CompilationUnit): RewriteSourcePositions {
            return try {
                val markerNodes = mutableMapOf<UUID, UUID>()
                val marked = object : GroovyVisitor<Unit>() {
                    override fun preVisit(tree: J, p: Unit): J {
                        val marker = SearchResult(UUID.randomUUID(), null)
                        markerNodes[marker.id] = tree.id
                        return tree.withMarkers(tree.markers.add(marker))
                    }
                }.visit(compilationUnit, Unit) as G.CompilationUnit

                val starts = mutableMapOf<UUID, Int>()
                val ends = mutableMapOf<UUID, Int>()
                val duplicates = mutableSetOf<UUID>()
                lateinit var output: PrintOutputCapture<Unit>
                val printer = object : PrintOutputCapture.MarkerPrinter {
                    override fun beforeSyntax(marker: Marker, cursor: Cursor, wrapper: UnaryOperator<String>): String {
                        markerNodes[marker.id]?.let { id ->
                            if (starts.put(id, output.out.length) != null) duplicates.add(id)
                        }
                        return ""
                    }

                    override fun afterSyntax(marker: Marker, cursor: Cursor, wrapper: UnaryOperator<String>): String {
                        markerNodes[marker.id]?.let { id ->
                            if (ends.put(id, output.out.length) != null) duplicates.add(id)
                        }
                        return ""
                    }
                }
                output = PrintOutputCapture(Unit, printer)
                GroovyPrinter<Unit>().visit(marked, output)
                if (output.out.toString() != source) return RewriteSourcePositions(emptyMap())

                val lineStarts = mutableListOf(0)
                source.forEachIndexed { index, char ->
                    if (char == '\n' || (char == '\r' && source.getOrNull(index + 1) != '\n')) {
                        lineStarts.add(index + 1)
                    }
                }
                fun position(offset: Int): Position {
                    val index = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
                    return Position(index + 1, offset - lineStarts[index] + 1)
                }
                val ranges = starts.mapNotNull { (id, start) ->
                    val end = ends[id]
                    if (id in duplicates || end == null) return@mapNotNull null
                    if (start > end || end > source.length) return@mapNotNull null
                    id to Range(position(start), position(end))
                }.toMap()
                RewriteSourcePositions(ranges)
            } catch (_: Exception) {
                // A position-mapping failure must not turn a successful parse into a failure.
                RewriteSourcePositions(emptyMap())
            }
        }
    }
}
