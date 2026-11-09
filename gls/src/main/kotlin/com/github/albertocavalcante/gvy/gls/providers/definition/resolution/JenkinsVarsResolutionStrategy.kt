package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import java.net.URI

/**
 * Resolves Jenkins vars/ global variables with resilient navigation support.
 *
 * Jenkins Shared Libraries define global variables as files in the `vars/` directory.
 * This strategy handles multiple access patterns:
 * - Method calls: `buildPlugin()` navigates to `vars/buildPlugin.groovy`
 * - Variable references: `infra` in `infra.checkoutSCM()` navigates to `vars/infra.groovy`
 *
 * **Priority: HIGHEST** - runs before any other resolution to catch Jenkins-specific patterns.
 *
 * ## Scope
 * Because this strategy runs first, it only claims references that Groovy would dispatch to
 * a Jenkins global at runtime; everything else falls through to [LocalSymbolResolutionStrategy]
 * and the remaining strategies.
 *
 * Script-scoped references (calls on `this`, implicit `deploy()` or explicit `this.deploy()`, and
 * variables the compiler left unresolved, i.e. no accessed variable or a [DynamicVariable]):
 * - The document must be in Jenkins mode ([isJenkinsModeEnabled], normally `groovy.mode`
 *   via `ModeResolver`, which applies the configured `jenkins.filePatterns`).
 * - The reference must be dispatched through the script instance: code inside a non-script class
 *   or a static method has no CpsScript `this`, so it is skipped.
 * - A member with the same name declared in the script class shadows the global, as it does in
 *   Groovy dispatch: methods, fields and properties shadow calls; fields and properties shadow
 *   variable references.
 *
 * Calls on a dynamically typed receiver (`steps.deploy()` where `steps` is an untyped, `def`,
 * `Object`, `Script` or `CpsScript` variable, parameter or field) resolve to vars/ in any file:
 * this is how shared-library `src/` classes reach globals, through `CpsScript.invokeMethod`.
 * Calls on statically typed receivers (`String s; s.size()`, `Helper h; h.deploy()`) and on other
 * receiver expressions are skipped.
 *
 * ## Node Handling
 * Supports both [MethodCallExpression] and [VariableExpression] to enable resilient navigation
 * for all Jenkins global variable usage patterns. Intentionally excludes ConstantExpression
 * to avoid false positives on string literals.
 *
 * @param isJenkinsModeEnabled whether Jenkins features apply to a document; defaults to
 *   file-pattern detection (the `groovy.mode=AUTO` behavior)
 * @param astModel AST model used to find same-script declarations; when null, shadowing by
 *   enclosing-class members is not checked
 */
class JenkinsVarsResolutionStrategy(
    private val jenkinsCapabilities: JenkinsCapabilities,
    private val isJenkinsModeEnabled: (URI) -> Boolean = jenkinsCapabilities::isJenkinsFile,
    private val astModel: GroovyAstModel? = null,
) : SymbolResolutionStrategy {

    private val logger = KotlinLogging.logger {}

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val reference = extractReference(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        val varName = reference.name

        skipReason(reference, context)?.let { reason ->
            return SymbolResolutionStrategy.notFound(reason, STRATEGY_NAME)
        }

        val globalVars = jenkinsCapabilities.getGlobalVariables()
        val matchingVar = globalVars.find { it.name == varName }
            ?: return SymbolResolutionStrategy.notFound(
                "No vars/$varName.groovy found (${globalVars.size} vars available)",
                STRATEGY_NAME,
            )

        logger.debug { "Found Jenkins global variable '$varName' at ${matchingVar.path}" }

        // Create a synthetic ClassNode to represent the definition location.
        // Uses the callLineNumber parsed from the vars file to navigate directly to `def call(...)`.
        val callLine = matchingVar.callLineNumber
        val syntheticNode = ClassNode(matchingVar.name, 0, null).apply {
            lineNumber = callLine
            columnNumber = 1
            lastLineNumber = callLine
            lastColumnNumber = 1
        }

        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(syntheticNode, matchingVar.path.toUri()),
        )
    }

    /** A name that Groovy could dispatch to a Jenkins global, and how it is reached. */
    private data class GlobalReference(val name: String, val viaDynamicReceiver: Boolean)

    /**
     * Extract a reference that Groovy could dispatch to a Jenkins global.
     *
     * Supports:
     * - [MethodCallExpression] on `this`: the method name (e.g., `buildPlugin` from `buildPlugin()`)
     * - [MethodCallExpression] on a dynamically typed variable (e.g., `deploy` from `steps.deploy()`)
     * - Unresolved [VariableExpression]: the variable name (e.g., `infra` from `infra.checkoutSCM()`)
     *
     * Returns null for other receivers, compiler-resolved variables, and unsupported node types
     * (e.g., ConstantExpression).
     */
    private fun extractReference(node: ASTNode): GlobalReference? = when (node) {
        is MethodCallExpression -> node.methodAsString?.let { name ->
            val receiver = node.objectExpression as? VariableExpression
            when {
                receiver == null || receiver.isSuperExpression -> null
                receiver.isThisExpression -> GlobalReference(name, viaDynamicReceiver = false)
                receiver.isDynamicallyTypedReceiver() -> GlobalReference(name, viaDynamicReceiver = true)
                else -> null
            }
        }
        is VariableExpression -> GlobalReference(node.name, viaDynamicReceiver = false)
            .takeIf { node.isUnresolvedReference() }
        else -> null
    }

    /** Why [reference] must not resolve to vars/ in this context, or null when it may. */
    private fun skipReason(reference: GlobalReference, context: ResolutionContext): String? {
        if (reference.viaDynamicReceiver) return null
        val scope = astModel?.let { enclosingScope(it, context) }
        val type = scope?.type
        val method = scope?.method
        return when {
            !isJenkinsModeEnabled(context.documentUri) -> "Document is not in Jenkins mode"
            type != null && !type.isScript -> "Enclosing class ${type.name} is not dispatched through the script"
            method != null && method.isStatic -> "Static method ${method.name} has no script instance"
            scope != null && scope.declares(reference.name, context.targetNode is MethodCallExpression) ->
                "'${reference.name}' is declared in the script class and shadows vars/"
            else -> null
        }
    }

    private fun VariableExpression.isUnresolvedReference(): Boolean {
        if (isThisExpression || isSuperExpression) return false
        val accessed = accessedVariable
        return accessed == null || accessed is DynamicVariable
    }

    /**
     * Whether calls on this receiver go through dynamic dispatch, which Jenkins routes to vars/ globals.
     * Uses the declared variable when the compiler bound one, otherwise this expression's own type.
     */
    private fun VariableExpression.isDynamicallyTypedReceiver(): Boolean {
        val variable: Variable = accessedVariable ?: this
        return variable is DynamicVariable ||
            variable.isDynamicTyped ||
            variable.originType.name in DYNAMIC_RECEIVER_TYPES
    }

    /** The method and class enclosing the target node, plus the fallback script classes. */
    private class EnclosingScope(
        val method: MethodNode?,
        val type: ClassNode?,
        private val fallbackScripts: () -> Sequence<ClassNode>,
    ) {
        /**
         * Method calls are shadowed by methods, fields and properties (a closure-valued field is callable);
         * variable references only by fields and properties, since `deploy.foo` is a property lookup.
         */
        fun declares(name: String, includeMethods: Boolean): Boolean =
            (type?.let { sequenceOf(it) } ?: fallbackScripts()).any { it.declaresMember(name, includeMethods) }

        private fun ClassNode.declaresMember(name: String, includeMethods: Boolean): Boolean =
            (includeMethods && getMethods(name).isNotEmpty()) ||
                getDeclaredField(name) != null ||
                getProperty(name) != null
    }

    /**
     * Find the method and class enclosing the target node.
     *
     * The class comes from the AST parent chain, using the declaring class of the first method,
     * field or property on the way. A script with no top-level statements gets no source position
     * for its script class, so the tracker never records that class as a parent; the declaring class
     * of the enclosing method still reaches it. When nothing on the chain names a class, shadowing
     * falls back to the script classes of the model, preferring the document's own.
     */
    private fun enclosingScope(model: GroovyAstModel, context: ResolutionContext): EnclosingScope {
        val chain = generateSequence(model.getParent(context.targetNode)) { model.getParent(it) }.toList()
        return EnclosingScope(
            method = chain.firstNotNullOfOrNull { it as? MethodNode },
            type = chain.firstNotNullOfOrNull { it.declaringClassOrSelf() },
            fallbackScripts = { scriptClassesOf(model, context.documentUri) },
        )
    }

    private fun ASTNode.declaringClassOrSelf(): ClassNode? = when (this) {
        is ClassNode -> this
        is MethodNode -> declaringClass
        is FieldNode -> declaringClass
        is PropertyNode -> declaringClass
        else -> null
    }

    /**
     * Script classes for [documentUri]. An untracked script class has no recorded URI, so classes
     * with an unknown URI are kept; at worst that skips vars/ lookup and defers to later strategies.
     */
    private fun scriptClassesOf(model: GroovyAstModel, documentUri: URI): Sequence<ClassNode> =
        model.getAllClassNodes().asSequence()
            .filter { it.isScript }
            .filter { cls -> model.getUri(cls).let { it == null || it == documentUri } }

    companion object {
        private const val STRATEGY_NAME = "JenkinsVars"

        /** Receiver types whose calls Groovy dispatches dynamically, reaching Jenkins globals at runtime. */
        private val DYNAMIC_RECEIVER_TYPES = setOf(
            "java.lang.Object",
            "groovy.lang.Script",
            "org.jenkinsci.plugins.workflow.cps.CpsScript",
        )
    }
}
