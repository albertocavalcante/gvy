package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.slf4j.Logger

object TypeResolutionHelper {

    data class VariableInitializerRefinementContext(
        val astModel: GroovyAstModel,
        val symbolTable: SymbolTable?,
        val logger: Logger?,
        val inferExpressionType: (Expression) -> String? = { TypeInferencer.inferExpressionType(it) },
    )

    fun resolveDataTypes(className: String, classpathService: ClasspathService): List<String> {
        if (classpathService.loadClass(className) != null) return listOf(className)

        if (!className.contains('.')) {
            val candidates = listOf(
                "java.lang.$className",
                "java.util.$className",
                "java.io.$className",
                "java.net.$className",
                "groovy.lang.$className",
                "groovy.util.$className",
            )
            return candidates.filter { classpathService.loadClass(it) != null }
        }

        return emptyList()
    }

    fun refineTypeFromVariableInitializer(
        inferredType: String?,
        objectExpr: Expression,
        ctx: VariableInitializerRefinementContext,
    ): String? {
        var type = inferredType

        val shouldAttemptRefinement =
            (type == "java.lang.Object" || type == "java.lang.Class") &&
                objectExpr is VariableExpression &&
                ctx.symbolTable != null

        if (shouldAttemptRefinement) {
            val resolvedVar: Variable? = ctx.symbolTable.resolveSymbol(objectExpr, ctx.astModel)
            val initExpr = resolvedVar
                ?.takeIf(Variable::hasInitialExpression)
                ?.initialExpression

            if (initExpr != null) {
                val inferred = runCatching { ctx.inferExpressionType(initExpr) }
                    .onFailure { ctx.logger?.debug("Type inference failed for variable initializer", it) }
                    .getOrNull()

                if (inferred != null && inferred != "java.lang.Object") {
                    type = inferred
                }
            }
        }

        return type
    }
}
