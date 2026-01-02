package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a map literal expression: `[key1: value1, key2: value2]`
 */
class MapExpr(entries: List<MapEntryExpr> = emptyList()) : Expression() {

    /** Entries of the map */
    val entries: MutableList<MapEntryExpr> = entries.toMutableList()

    init {
        entries.forEach { setAsParentNodeOf(it) }
    }

    fun addEntry(entry: MapEntryExpr) {
        entries.add(entry)
        setAsParentNodeOf(entry)
    }

    override fun getChildNodes(): List<Node> = entries.toList()

    override fun toString(): String = "MapExpr[${entries.size} entry(ies)]"
}

/**
 * Represents a map entry: `key: value`
 */
class MapEntryExpr(val key: Expression, val value: Expression) : Expression() {

    init {
        setAsParentNodeOf(key)
        setAsParentNodeOf(value)
    }

    override fun getChildNodes(): List<Node> = listOf(key, value)

    override fun toString(): String = "MapEntryExpr[$key: $value]"
}
