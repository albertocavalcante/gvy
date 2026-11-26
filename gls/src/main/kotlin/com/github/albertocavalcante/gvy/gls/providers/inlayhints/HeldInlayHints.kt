// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.gvy.gls.compilation.NativeQueryContext
import com.github.albertocavalcante.gvy.gls.services.QualifiedGdkMethod
import com.github.albertocavalcante.gvy.gls.services.heldGdkMethods
import com.github.albertocavalcante.gvy.gls.services.reflectSafely
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

private val logger = KotlinLogging.logger {}

internal suspend fun heldInlayHints(query: NativeQueryContext, uri: URI, range: Range): List<InlayHint> {
    val parsed = query.held.parseResult
    val module = parsed.ast?.takeIf { it.context === parsed.sourceUnit && it.context.inputDocumentUri() == uri }
        ?: return emptyList()
    val caller = currentCoroutineContext()
    val candidates = HeldInlayCandidates(query)
    return buildList {
        for (node in parsed.astModel.getAllNodes()) {
            caller.ensureActive()
            if (node.lineNumber - 1 !in range.start.line..range.end.line || parsed.astModel.getUri(node) != uri) {
                continue
            }
            when (node) {
                is DeclarationExpression -> addAll(typeHints(node, query.types, module, logger))
                is ConstructorCallExpression -> addAll(candidates.constructorHints(node))
                is MethodCallExpression -> addAll(candidates.methodHints(node))
            }
        }
    }
}

/** Source candidates come from the exact declaration selected in the caller's compilation unit. */
private class HeldInlayCandidates(private val query: NativeQueryContext) {
    private val parsed = query.held.parseResult
    private val model = parsed.astModel
    private val reflection = query.reflection
    private val reassigned by lazy { model.reassignedLocals() }
    private val extensions = mutableMapOf<Pair<String, String>, List<QualifiedGdkMethod>>()

    fun constructorHints(call: ConstructorCallExpression): List<InlayHint> {
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()
        if (arguments.expressions.isEmpty()) return emptyList()
        val receiver = call.type.redirect()
        val count = arguments.expressions.size
        return hints(arguments, {
            when {
                sourceDeclaration(receiver) -> receiver.declaredConstructors
                    .filter { it.parameters.size == count }
                    .map { InlayHintsTypes.toSignature(it.parameters.asList()) }
                binaryDeclaration(receiver) -> reflection.loadClass(receiver.name)?.let {
                    InlayHintsCandidates.classpathConstructorSignatures(it, count)
                }.orEmpty()
                else -> emptyList()
            }
        })
    }

    fun methodHints(call: MethodCallExpression): List<InlayHint> {
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()
        if (arguments.expressions.isEmpty()) return emptyList()
        val name = call.methodAsString ?: return emptyList()
        val receiver = selectedReceiver(call)?.redirect() ?: return emptyList()
        val source = sourceDeclaration(receiver)
        if (!source && !binaryDeclaration(receiver)) return emptyList()
        val count = arguments.expressions.size
        val static = call.objectExpression is ClassExpression
        return hints(
            arguments,
            {
                if (source) {
                    receiver.getDeclaredMethods(name)
                        .filter { it.parameters.size == count && (!static || it.isStatic) }
                        .map { InlayHintsTypes.toSignature(it.parameters.asList()) }
                } else {
                    emptyList()
                }
            },
            {
                gdkMethods(receiver, name).filter { it.qualifiedParameters.size == count }.map {
                    InlayHintsTypes.toSignature(it.qualifiedParameters, it.display.parameterNames)
                }
            },
            {
                if (source) {
                    emptyList()
                } else {
                    reflection.getMethods(receiver.name)
                        .filter { it.name == name && it.parameters.size == count && (!static || it.isStatic) }
                        .map { InlayHintsTypes.toSignature(it.qualifiedParameters, it.parameterNames) }
                }
            },
        )
    }

    private fun hints(
        arguments: ArgumentListExpression,
        vararg providers: () -> List<CallableSignature>,
    ): List<InlayHint> {
        val types = InlayHintsCandidates.resolveArgumentTypes(arguments.expressions, query.types, parsed.ast)
        val answer = InlayHintsCandidates.resolveFromCandidates(types, reflection::loadClass, *providers)
        val labels = (answer as? ResolutionResult.Match)?.parameterNames.orEmpty()
        return createHintsForArguments(arguments, labels)
    }

    private fun sourceDeclaration(type: ClassNode): Boolean {
        if (!type.isPrimaryClassNode) return false
        val module = type.module ?: return false
        return module.context?.inputDocumentUri() != null && parsed.compilationUnit.ast.modules.any { it === module } &&
            module.classes.any { it === type }
    }

    private fun binaryDeclaration(type: ClassNode): Boolean = !type.isPrimaryClassNode && type.isResolved

    private fun selectedReceiver(call: MethodCallExpression): ClassNode? {
        val expression = call.objectExpression
        if (call.isImplicitThis || expression is VariableExpression && expression.isThisExpression) {
            var node: ASTNode? = call
            val seen = Collections.newSetFromMap(IdentityHashMap<ASTNode, Boolean>())
            while (node != null && seen.add(node) && node !is ClassNode) {
                node = model.getParent(node)
            }
            return node as? ClassNode
        }
        if (expression !is VariableExpression) return expression?.type?.takeUnless { it == ClassHelper.OBJECT_TYPE }
        return when (val binding = expression.accessedVariable) {
            is Parameter -> binding.type
            is FieldNode -> binding.type
            is VariableExpression -> localType(binding)
            else -> null
        }
    }

    private fun localType(binding: VariableExpression): ClassNode? {
        if (binding.accessedVariable != null && binding.accessedVariable !== binding) return null
        if (!binding.isDynamicTyped) return binding.type
        if (binding in reassigned) return null
        val declaration = model.getParent(binding) as? DeclarationExpression ?: return null
        val initializer: Expression = declaration.rightExpression
        return initializer.type.takeUnless { it == ClassHelper.OBJECT_TYPE }
    }

    private fun gdkMethods(receiver: ClassNode, name: String): List<QualifiedGdkMethod> {
        val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
        val pending = ArrayDeque<ClassNode>().apply { add(receiver) }
        val result = mutableListOf<QualifiedGdkMethod>()
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst().redirect()
            if (!seen.add(type)) continue
            when {
                binaryDeclaration(type) -> result += extensions.getOrPut(type.name to name) {
                    reflectSafely({ "GDK methods named $name for ${type.name}" }, emptyList()) {
                        heldGdkMethods(reflection, type.name, name)
                    }
                }
                sourceDeclaration(type) -> {
                    type.interfaces.forEach(pending::addLast)
                    type.superClass?.let(pending::addLast)
                }
            }
        }
        return result.distinctBy { it.display.name to it.qualifiedParameters }
    }
}
