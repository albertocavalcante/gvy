package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents an annotation: `@Annotation` or `@Annotation(value)` or `@Annotation(key = value)`
 *
 * Common Groovy annotations include:
 * - `@ToString`, `@EqualsAndHashCode`, `@Builder`
 * - `@CompileStatic`, `@TypeChecked`
 * - `@Field`, `@Grab`
 *
 * Jenkins-specific:
 * - `@NonCPS` - marks methods that shouldn't be CPS-transformed
 */
class AnnotationExpr(val name: String) : Node() {

    /** Annotation members as key-value pairs */
    val members: MutableMap<String, Expression> = mutableMapOf()

    /** Single value annotation (unnamed member) */
    var value: Expression? = null
        set(value) {
            field = value
            value?.let { setAsParentNodeOf(it) }
        }

    /**
     * Adds a named member to the annotation.
     */
    fun addMember(name: String, value: Expression) {
        members[name] = value
        setAsParentNodeOf(value)
    }

    /**
     * Gets the full name of the annotation (without @).
     */
    val fullName: String
        get() = name

    /**
     * Returns true if this is a marker annotation (no members).
     */
    val isMarker: Boolean
        get() = value == null && members.isEmpty()

    /**
     * Returns true if this is a single-value annotation.
     */
    val isSingleValue: Boolean
        get() = value != null && members.isEmpty()

    override fun getChildNodes(): List<Node> = buildList {
        value?.let { add(it) }
        addAll(members.values)
    }

    override fun toString(): String = when {
        isMarker -> "@$name"
        isSingleValue -> "@$name($value)"
        else -> "@$name(${members.entries.joinToString(", ") { "${it.key}=${it.value}" }})"
    }
}
