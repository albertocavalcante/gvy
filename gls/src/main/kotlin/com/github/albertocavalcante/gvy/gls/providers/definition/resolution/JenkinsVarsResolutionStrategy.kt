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
 * and the remaining strategies:
 * - The document must be in Jenkins mode ([isJenkinsModeEnabled], normally `groovy.mode`
 *   via `ModeResolver`, which applies the configured `jenkins.filePatterns`).
 * - Method calls must target `this` (implicit `deploy()` or explicit `this.deploy()`); calls on
 *   any other receiver such as `helper.deploy()` or `steps.deploy()` are skipped.
 * - Variable references must be unresolved by the compiler (no accessed variable, or a
 *   [DynamicVariable]); locals, parameters and fields are skipped.
 * - A member with the same name declared in the enclosing class (the script class for a
 *   Jenkinsfile or vars/ file) shadows the global, as it does in Groovy dispatch: methods, fields
 *   and properties shadow calls; fields and properties shadow variable references.
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
        val varName = extractVariableName(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        skipReason(varName, context)?.let { reason ->
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

    /**
     * Extract the name of a reference that Groovy could dispatch to a Jenkins global.
     *
     * Supports:
     * - [MethodCallExpression] on `this`: the method name (e.g., `buildPlugin` from `buildPlugin()`)
     * - Unresolved [VariableExpression]: the variable name (e.g., `infra` from `infra.checkoutSCM()`)
     *
     * Returns null for other receivers, compiler-resolved variables, and unsupported node types
     * (e.g., ConstantExpression).
     */
    private fun extractVariableName(node: ASTNode): String? = when (node) {
        is MethodCallExpression -> node.methodAsString?.takeIf { node.isCallOnThis() }
        is VariableExpression -> node.name.takeIf { node.isUnresolvedReference() }
        else -> null
    }

    /** Why [varName] must not resolve to vars/ in this context, or null when it may. */
    private fun skipReason(varName: String, context: ResolutionContext): String? = when {
        !isJenkinsModeEnabled(context.documentUri) -> "Document is not in Jenkins mode"
        isDeclaredInEnclosingClass(varName, context) ->
            "'$varName' is declared in the enclosing class and shadows vars/"
        else -> null
    }

    private fun MethodCallExpression.isCallOnThis(): Boolean =
        (objectExpression as? VariableExpression)?.isThisExpression == true

    private fun VariableExpression.isUnresolvedReference(): Boolean {
        if (isThisExpression || isSuperExpression) return false
        val accessed = accessedVariable
        return accessed == null || accessed is DynamicVariable
    }

    /**
     * Whether a member named [name] is declared in a class enclosing the target node.
     *
     * Method calls are shadowed by methods, fields and properties (a closure-valued field is callable);
     * variable references only by fields and properties, since `deploy.foo` is a property lookup.
     *
     * The enclosing class comes from the AST parent chain, using the declaring class of the first
     * method, field or property on the way. A script with no top-level statements gets no source
     * position for its script class, so the tracker never records that class as a parent; the
     * declaring class of the enclosing method still reaches it. When nothing on the chain names a
     * class, falls back to the script classes of the model, preferring the document's own.
     */
    private fun isDeclaredInEnclosingClass(name: String, context: ResolutionContext): Boolean {
        val model = astModel ?: return false
        val includeMethods = context.targetNode is MethodCallExpression
        val enclosing = generateSequence(model.getParent(context.targetNode)) { model.getParent(it) }
            .firstNotNullOfOrNull { it.declaringClassOrSelf() }
        val candidates = enclosing?.let { generateSequence(it) { cls -> cls.outerClass } }
            ?: scriptClassesOf(model, context.documentUri)
        return candidates.any { it.declaresMember(name, includeMethods) }
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

    private fun ClassNode.declaresMember(name: String, includeMethods: Boolean): Boolean =
        (includeMethods && getMethods(name).isNotEmpty()) ||
            getDeclaredField(name) != null ||
            getProperty(name) != null

    companion object {
        private const val STRATEGY_NAME = "JenkinsVars"
    }
}
