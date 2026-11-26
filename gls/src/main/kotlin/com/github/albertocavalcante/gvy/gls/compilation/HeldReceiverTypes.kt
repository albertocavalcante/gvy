// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.inlayhints.reassignedLocals
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import java.util.Collections
import java.util.IdentityHashMap

/** Follow declarations selected by this compilation. Only compiler-resolved binary nodes enter reflection. */
internal class HeldReceiverTypes(query: NativeQueryContext) {
    private val parse = query.held.parseResult
    private val model = parse.astModel
    private val reassigned by lazy { model.reassignedLocals() }

    fun lexicalOwner(anchor: ASTNode): ClassNode? {
        val seen = Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())
        var node: ASTNode? = anchor
        while (node != null && node !is ClassNode && seen.add(node)) node = model.getParent(node)
        return node as? ClassNode
    }

    /** Only the variable's own declaration or its unreassigned initializer can establish its type. */
    fun bindingType(expression: VariableExpression): ClassNode? = when (val binding = expression.accessedVariable) {
        is Parameter -> binding.type
        is FieldNode -> binding.type
        is VariableExpression -> localType(binding)
        else -> null
    }

    private fun localType(binding: VariableExpression): ClassNode? {
        if (binding.accessedVariable != null && binding.accessedVariable !== binding) return null
        if (!binding.isDynamicTyped) return binding.type
        if (binding in reassigned) return null
        val declaration = model.getParent(binding) as? DeclarationExpression ?: return null
        return declaration.rightExpression.type.takeUnless { it == ClassHelper.OBJECT_TYPE }
    }

    fun isSource(type: ClassNode): Boolean {
        val module = type.module ?: return false
        return type.isPrimaryClassNode && module.context?.inputDocumentUri() != null &&
            parse.compilationUnit.ast.modules.any { it === module } && module.classes.any { it === type }
    }

    fun isBinary(type: ClassNode): Boolean = !type.isPrimaryClassNode && type.isResolved

    /** The first binary types reached along each actual class/interface edge. */
    fun binaryAncestors(receiver: ClassNode): List<String> {
        val result = linkedSetOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
        val pending = ArrayDeque<ClassNode>().apply { add(receiver) }
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst().redirect()
            if (!seen.add(type)) continue
            when {
                isBinary(type) -> result.add(type.name)
                isSource(type) -> {
                    type.interfaces.forEach(pending::addLast)
                    type.superClass?.let(pending::addFirst)
                }
            }
        }
        return result.toList()
    }
}
