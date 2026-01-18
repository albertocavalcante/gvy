package com.github.albertocavalcante.groovylsp.providers.inlayhints

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Strategy for generating parameter name inlay hints for method and constructor calls.
 *
 * This strategy shows parameter names at call sites for positional arguments.
 * For example:
 * ```groovy
 * processFile("input.txt", true)  // Shows: processFile(path: "input.txt", verbose: true)
 * new ArrayList(10)               // Shows: new ArrayList(initialCapacity: 10)
 * ```
 *
 * Parameter hints are shown when:
 * - The call has at least one argument
 * - Parameter names can be resolved from AST, workspace symbols, or classpath
 * - The argument is not a closure (closures provide their own context)
 *
 * Resolution order for parameter names:
 * 1. Same-file AST methods/constructors
 * 2. Workspace symbols (cross-file)
 * 3. GDK methods (Groovy extension methods)
 * 4. Classpath reflection (fallback)
 */
class ParameterInlayHintStrategy : InlayHintStrategy {
    override fun canHandle(node: ASTNode, context: HintContext): Boolean = context.config.parameterHints &&
        (node is MethodCallExpression || node is ConstructorCallExpression)

    override fun generateHints(node: ASTNode, context: HintContext): List<InlayHint> = when (node) {
        is MethodCallExpression -> generateMethodParameterHints(node, context)
        is ConstructorCallExpression -> generateConstructorParameterHints(node, context)
        else -> emptyList()
    }

    private fun generateMethodParameterHints(call: MethodCallExpression, context: HintContext): List<InlayHint> {
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()

        if (arguments.expressions.isEmpty()) {
            return emptyList()
        }

        // Try to resolve the method to get parameter names
        val parameterNames = resolveMethodParameterNames(call, context)
        if (parameterNames.isEmpty()) {
            return emptyList()
        }

        // Generate hints for each argument
        return arguments.expressions.mapIndexedNotNull { index, arg ->
            if (index >= parameterNames.size) return@mapIndexedNotNull null

            val paramName = parameterNames[index]

            // Skip if argument is a closure (they provide their own context)
            if (arg is ClosureExpression) {
                return@mapIndexedNotNull null
            }

            val position = Position(
                arg.lineNumber - 1,
                arg.columnNumber - 1,
            )

            InlayHint(position, Either.forLeft("$paramName:")).apply {
                kind = InlayHintKind.Parameter
                paddingRight = true
            }
        }
    }

    private fun generateConstructorParameterHints(
        call: ConstructorCallExpression,
        context: HintContext,
    ): List<InlayHint> {
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()

        if (arguments.expressions.isEmpty()) {
            return emptyList()
        }

        // Try to resolve constructor parameter names
        val parameterNames = resolveConstructorParameterNames(call, context)
        if (parameterNames.isEmpty()) {
            return emptyList()
        }

        return arguments.expressions.mapIndexedNotNull { index, arg ->
            if (index >= parameterNames.size) return@mapIndexedNotNull null

            val paramName = parameterNames[index]

            if (arg is ClosureExpression) {
                return@mapIndexedNotNull null
            }

            val position = Position(
                arg.lineNumber - 1,
                arg.columnNumber - 1,
            )

            InlayHint(position, Either.forLeft("$paramName:")).apply {
                kind = InlayHintKind.Parameter
                paddingRight = true
            }
        }
    }

    /**
     * Resolve parameter names for a method call.
     *
     * Resolution stages:
     *  1) same-file AST
     *  2) workspace symbols (requires receiver type)
     *  3) GDK methods (Groovy extensions)
     *  4) classpath reflection (requires receiver type)
     */
    private fun resolveMethodParameterNames(call: MethodCallExpression, context: HintContext): List<String> {
        val methodName = call.methodAsString ?: return emptyList()
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()
        val argCount = arguments.expressions.size
        val argumentTypes =
            InlayHintsCandidates.resolveArgumentTypes(
                arguments.expressions,
                context.semanticResolver,
                context.moduleNode,
            )
        val receiverType =
            InlayHintsCandidates.resolveReceiverType(call, context)
        val isStaticCall = call.objectExpression is ClassExpression

        // Resolution order:
        // 1. AST methods (same file)
        // 2. Workspace methods (cross-file)
        // 3. GDK methods (Groovy extensions)
        // 4. Classpath methods (fallback)
        val result = InlayHintsCandidates.resolveFromCandidates(
            argumentTypes,
            context.compilationService,
            {
                InlayHintsCandidates.findMethodCandidatesInAst(
                    context.astModel,
                    methodName,
                    argCount,
                    receiverType,
                    isStaticCall,
                )
            },
            {
                InlayHintsCandidates.findWorkspaceMethodCandidates(
                    methodName,
                    argCount,
                    receiverType,
                    isStaticCall,
                    context.workspaceSymbols,
                )
            },
            {
                InlayHintsCandidates.findGdkMethodCandidates(
                    methodName,
                    argCount,
                    receiverType,
                    context.compilationService,
                )
            },
            {
                InlayHintsCandidates.findClasspathMethodCandidates(
                    methodName,
                    argCount,
                    receiverType,
                    isStaticCall,
                    context.compilationService,
                )
            },
        )
        return (result as? ResolutionResult.Match)?.parameterNames.orEmpty()
    }

    /**
     * Resolve parameter names for a constructor call.
     */
    private fun resolveConstructorParameterNames(call: ConstructorCallExpression, context: HintContext): List<String> {
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()
        val argCount = arguments.expressions.size
        val argumentTypes =
            InlayHintsCandidates.resolveArgumentTypes(
                arguments.expressions,
                context.semanticResolver,
                context.moduleNode,
            )
        val typeName = call.type.name

        val result = InlayHintsCandidates.resolveFromCandidates(
            argumentTypes,
            context.compilationService,
            { InlayHintsCandidates.findConstructorCandidatesInAst(context.astModel, typeName, argCount) },
            { InlayHintsCandidates.findWorkspaceConstructorCandidates(typeName, argCount, context.workspaceSymbols) },
            {
                InlayHintsCandidates.findClasspathConstructorCandidates(
                    typeName,
                    argCount,
                    context.compilationService,
                )
            },
        )
        return (result as? ResolutionResult.Match)?.parameterNames.orEmpty()
    }
}
