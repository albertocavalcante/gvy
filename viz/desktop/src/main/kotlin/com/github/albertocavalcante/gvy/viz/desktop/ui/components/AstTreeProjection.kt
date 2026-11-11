package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto

/** Equal DTO values can belong to different parses; Compose state must follow reference identity. */
internal class AstTreeSnapshot(private val tree: AstNodeDto?) {
    override fun equals(other: Any?): Boolean = other is AstTreeSnapshot && tree === other.tree
    override fun hashCode(): Int = System.identityHashCode(tree)
}

internal data class AstTreeRow(
    val node: AstNodeDto,
    val depth: Int,
    val parentId: String?,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
)

/** Index one parse snapshot without copying DTOs or recursively walking deep trees. */
internal class AstTreeProjection(private val root: AstNodeDto) {
    private data class Entry(val node: AstNodeDto, val depth: Int, val parentId: String?)

    private val entries = buildList {
        val pending = ArrayDeque<Entry>()
        pending.add(Entry(root, 0, null))
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            add(entry)
            entry.node.children.asReversed().forEach { child ->
                pending.add(Entry(child, entry.depth + 1, entry.node.id))
            }
        }
    }
    private val byId = entries.associateBy { it.node.id }

    fun pathTo(node: AstNodeDto): List<String> {
        if (byId[node.id]?.node !== node) return emptyList()
        val path = mutableListOf<String>()
        var current: String? = node.id
        while (current != null) {
            path.add(current)
            current = byId[current]?.parentId
        }
        return path.asReversed()
    }

    /** A null filter means all nodes; active filters include matching nodes and their ancestors. */
    fun matchingIds(query: String): Set<String>? {
        if (query.isBlank()) return null
        val included = mutableSetOf<String>()
        for (entry in entries.asReversed()) {
            if (entry.node.type.contains(query, ignoreCase = true) ||
                entry.node.properties["name"]?.contains(query, ignoreCase = true) == true
            ) {
                included.add(entry.node.id)
            }
            if (entry.node.id in included) entry.parentId?.let(included::add)
        }
        return included
    }

    fun visibleRows(expansion: Map<String, Boolean>, included: Set<String>?): List<AstTreeRow> = buildList {
        val pending = ArrayDeque<Entry>()
        pending.add(Entry(root, 0, null))
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (included != null && entry.node.id !in included) continue
            val children = entry.node.children.filter { included == null || it.id in included }
            val expanded = children.isNotEmpty() &&
                (included != null || (expansion[entry.node.id] ?: (entry.depth < 2)))
            add(AstTreeRow(entry.node, entry.depth, entry.parentId, children.isNotEmpty(), expanded))
            if (expanded) {
                children.asReversed().forEach { child ->
                    pending.add(Entry(child, entry.depth + 1, entry.node.id))
                }
            }
        }
    }
}
