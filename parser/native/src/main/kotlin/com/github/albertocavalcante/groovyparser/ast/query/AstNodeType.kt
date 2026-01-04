package com.github.albertocavalcante.groovyparser.ast.query

import org.codehaus.groovy.ast.ASTNode

object AstNodeType {
    private const val SNAKE_CASE_EXTRA_CAPACITY = 8

    fun matches(node: ASTNode, queryType: String): Boolean {
        val trimmed = queryType.trim()
        if (trimmed.isEmpty()) return false
        val simpleName = node.javaClass.simpleName
        return if (isSnakeQuery(trimmed)) {
            toSnakeCase(simpleName).equals(trimmed, ignoreCase = true)
        } else {
            simpleName == trimmed
        }
    }

    private fun isSnakeQuery(value: String): Boolean = value.any { it == '_' } || value.all { it.isLowerCase() }

    private fun toSnakeCase(value: String): String {
        if (value.isEmpty()) return value
        val result = StringBuilder(value.length + SNAKE_CASE_EXTRA_CAPACITY)
        value.forEachIndexed { index, ch ->
            if (ch.isUpperCase()) {
                if (index > 0) {
                    val prev = value[index - 1]
                    val next = value.getOrNull(index + 1)
                    if (prev.isLowerCase() || (next != null && next.isLowerCase())) {
                        result.append('_')
                    }
                }
                result.append(ch.lowercaseChar())
            } else {
                result.append(ch)
            }
        }
        return result.toString()
    }
}
