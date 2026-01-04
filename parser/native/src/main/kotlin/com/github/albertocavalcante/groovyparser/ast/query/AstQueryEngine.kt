package com.github.albertocavalcante.groovyparser.ast.query

import org.codehaus.groovy.ast.ASTNode
import java.util.Collections
import java.util.IdentityHashMap

class AstQueryEngine(private val childrenProvider: (ASTNode) -> List<ASTNode>) {
    fun find(root: ASTNode, query: AstQuery): List<AstQueryMatch> {
        val matches = mutableListOf<AstQueryMatch>()
        val stack = ArrayDeque<ASTNode>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            query.patterns.forEach { pattern ->
                val captures = match(node, pattern)
                if (captures != null) {
                    matches.add(AstQueryMatch(node, captures))
                }
            }
            childrenProvider(node).forEach { child -> stack.add(child) }
        }

        return matches
    }

    private fun match(node: ASTNode, pattern: AstQueryPattern): Map<String, ASTNode>? {
        if (!AstNodeType.matches(node, pattern.type)) return null

        val captures = mutableMapOf<String, ASTNode>()
        if (pattern.capture != null && !captures.mergeCapture(pattern.capture, node)) {
            return null
        }

        val children = childrenProvider(node)
        val usedChildren = Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())

        val matchesAllChildren = pattern.children.all { childPattern ->
            val matchResult = children.asSequence()
                .filterNot { it in usedChildren }
                .firstNotNullOfOrNull { child ->
                    match(child, childPattern)?.let { child to it }
                }

            if (matchResult == null) {
                false
            } else {
                val (matchedChild, childCaptures) = matchResult
                usedChildren.add(matchedChild)
                captures.mergeCaptures(childCaptures)
            }
        }

        return captures.takeIf { matchesAllChildren }
    }

    private fun MutableMap<String, ASTNode>.mergeCapture(name: String, node: ASTNode): Boolean {
        val existing = this[name]
        if (existing == null) {
            this[name] = node
            return true
        }
        return existing === node
    }

    private fun MutableMap<String, ASTNode>.mergeCaptures(captures: Map<String, ASTNode>): Boolean {
        captures.forEach { (key, value) ->
            if (!mergeCapture(key, value)) {
                return false
            }
        }
        return true
    }
}
