// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.jenkins

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.definition.resolution.EnclosingScriptScope
import com.github.albertocavalcante.gvy.gls.providers.definition.resolution.findEnclosingScope
import com.github.albertocavalcante.gvy.jenkins.metadata.Availability
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.OwnerEvidence
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import java.net.URI

/** A structural match with unresolved ownership is not evidence of a Jenkins binding. */
internal enum class CredentialBindingSite { NOT_BINDING, BINDING, UNKNOWN }

private data class CredentialCall(
    val call: MethodCallExpression,
    val list: ListExpression,
    val outer: MethodCallExpression,
)

/**
 * A bounded structural fallback for a direct first-list argument of withCredentials.
 * A degraded parse, missing lexical scope, known local/library declaration, or alternate target fails closed.
 * It cannot prove arbitrary Groovy closure delegate dispatch; shared contextual resolution must refine this.
 */
internal fun GroovyAstModel.credentialBindingSite(
    call: MethodCallExpression,
    uri: URI,
    metadata: MergedJenkinsMetadata,
    capabilities: JenkinsCapabilities?,
    reliableSource: Boolean,
): CredentialBindingSite {
    val site = credentialCall(call) ?: return CredentialBindingSite.NOT_BINDING
    return classifyCredentialCall(site, uri, metadata, capabilities, reliableSource)
}

private fun GroovyAstModel.credentialCall(call: MethodCallExpression): CredentialCall? {
    val list = (getParent(call) as? ListExpression)?.takeIf { it.expressions.any { child -> child === call } }
        ?: return null
    val outer = credentialWrapper(list) ?: return null
    return CredentialCall(call, list, outer)
}

private fun GroovyAstModel.credentialWrapper(list: ListExpression): MethodCallExpression? {
    val outer = getParent(list) as? MethodCallExpression ?: return null
    if (outer.methodAsString != "withCredentials") return null
    return outer.takeIf {
        val args = outer.arguments as? TupleExpression
        args != null && outer.arguments === args && args.expressions.firstOrNull() === list
    }
}

private fun GroovyAstModel.classifyCredentialCall(
    site: CredentialCall,
    uri: URI,
    metadata: MergedJenkinsMetadata,
    capabilities: JenkinsCapabilities?,
    reliableSource: Boolean,
): CredentialBindingSite {
    val name = site.call.methodAsString ?: return CredentialBindingSite.UNKNOWN
    if (listOf(site.call, site.list, site.outer).any { getUri(it) != uri }) return CredentialBindingSite.UNKNOWN
    val eligible =
        reliableSource &&
            capabilities != null &&
            isEligibleCredentialCall(site, name, uri, metadata, capabilities)
    return if (eligible) CredentialBindingSite.BINDING else CredentialBindingSite.UNKNOWN
}

private fun GroovyAstModel.isEligibleCredentialCall(
    site: CredentialCall,
    name: String,
    uri: URI,
    metadata: MergedJenkinsMetadata,
    capabilities: JenkinsCapabilities,
): Boolean = site.hasSafeDispatch() &&
    hasUnshadowedScope(site, uri, name, capabilities) &&
    metadata.hasAttributedBindingWrapper(name)

private fun CredentialCall.hasSafeDispatch(): Boolean =
    hasImplicitReceivers() && hasNoDispatchOperators() && hasNoAlternateTargets()

private fun CredentialCall.hasImplicitReceivers(): Boolean = call.isImplicitThis && outer.isImplicitThis

private fun CredentialCall.hasNoDispatchOperators(): Boolean {
    if (call.isSafe || outer.isSafe) return false
    return !call.isSpreadSafe && !outer.isSpreadSafe
}

private fun CredentialCall.hasNoAlternateTargets(): Boolean {
    if (call.methodTarget != null || outer.methodTarget != null) return false
    return call.getNodeMetaData<Any>("targetMethod") !is MethodNode &&
        outer.getNodeMetaData<Any>("targetMethod") !is MethodNode
}

private fun GroovyAstModel.hasUnshadowedScope(
    site: CredentialCall,
    uri: URI,
    name: String,
    capabilities: JenkinsCapabilities,
): Boolean {
    // A workspace vars/ declaration, script member, or lexical variable can own either call.
    if (capabilities.getGlobalVariables().any { it.name == name || it.name == "withCredentials" }) return false
    val scope = findEnclosingScope(this, site.call, uri)
    val scripts = getAllClassNodes().filter { it.isScriptSafe() && getUri(it) == uri }
    return scope.hasRegisteredOwner(scripts, name) && hasUnshadowedAncestors(site, uri, name)
}

private fun EnclosingScriptScope.hasRegisteredOwner(scripts: List<ClassNode>, name: String): Boolean {
    val owner = type ?: return false
    return scripts.any { it === owner } && method?.isStatic != true && hasNoMemberShadow(name)
}

private fun EnclosingScriptScope.hasNoMemberShadow(name: String): Boolean =
    !declares(name, includeMethods = true) && !declares("withCredentials", includeMethods = true)

private enum class ScopeEvidence { NOT_SCOPE, CLEAR, INVALID }

private fun GroovyAstModel.hasUnshadowedAncestors(site: CredentialCall, uri: URI, name: String): Boolean {
    var sawScope = false
    for (ancestor in generateSequence(getParent(site.call)) { getParent(it) }) {
        when (scopeEvidence(ancestor, uri, name)) {
            ScopeEvidence.INVALID -> return false
            ScopeEvidence.CLEAR -> sawScope = true
            ScopeEvidence.NOT_SCOPE -> Unit
        }
    }
    return sawScope || DirectScriptScope.run { hasDirectScriptScopes(site, uri, name) }
}

private fun GroovyAstModel.scopeEvidence(node: ASTNode, uri: URI, name: String): ScopeEvidence {
    if (getUri(node) != uri) return ScopeEvidence.INVALID
    return when (node) {
        is ClosureExpression -> node.variableScope.scopeEvidence(name)
        is BlockStatement -> node.variableScope.scopeEvidence(name)
        is MethodNode -> node.variableScope.scopeEvidence(name)
        else -> ScopeEvidence.NOT_SCOPE
    }
}

private fun VariableScope?.scopeEvidence(name: String): ScopeEvidence = when {
    this == null -> ScopeEvidence.INVALID
    !hasNoBindingShadow(name) -> ScopeEvidence.INVALID
    else -> ScopeEvidence.CLEAR
}

private object DirectScriptScope {
    /** The visitor attaches direct script statements to their script class, not the script body block. */
    fun GroovyAstModel.hasDirectScriptScopes(site: CredentialCall, uri: URI, name: String): Boolean {
        val statement = getParent(site.outer) as? ExpressionStatement ?: return false
        if (statement.expression !== site.outer) return false
        val script = getParent(statement) as? ClassNode
        return script != null && hasRegisteredDirectRoot(site, statement, script, uri, name)
    }

    private fun GroovyAstModel.hasRegisteredDirectRoot(
        site: CredentialCall,
        statement: ExpressionStatement,
        script: ClassNode,
        uri: URI,
        name: String,
    ): Boolean {
        if (!script.isScriptSafe() || getAllClassNodes().none { it === script }) return false
        if (listOf(site.call, site.list, site.outer, statement, script).any { getUri(it) != uri }) return false
        return script.hasDirectStatementScopes(statement, name)
    }

    private fun ClassNode.hasDirectStatementScopes(statement: ExpressionStatement, name: String): Boolean {
        val block = module?.statementBlock ?: return false
        if (block.statements.none { it === statement }) return false
        return hasScriptBodyScopes(block, name)
    }

    private fun ClassNode.hasScriptBodyScopes(block: BlockStatement, name: String): Boolean {
        val body = methods.filter { it.declaringClass === this && it.isScriptBody }.singleOrNull() ?: return false
        return !body.isStatic && body.code === block && hasClearScriptScopes(block, body, name)
    }

    private fun hasClearScriptScopes(block: BlockStatement, body: MethodNode, name: String): Boolean {
        val blockScope = block.variableScope ?: return false
        val bodyScope = body.variableScope ?: return false
        return blockScope.hasNoBindingShadow(name) && bodyScope.hasNoBindingShadow(name)
    }
}

private fun VariableScope.hasNoBindingShadow(name: String): Boolean =
    getDeclaredVariable(name) == null && getDeclaredVariable("withCredentials") == null

private fun MergedJenkinsMetadata.hasAttributedBindingWrapper(name: String): Boolean {
    val wrapper = catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "withCredentials").singleOrNull() ?: return false
    return wrapper.hasBindingOwner() &&
        wrapper.availability != Availability.UNAVAILABLE &&
        catalogue.candidates(SymbolKind.EXECUTABLE_STEP, name).isEmpty()
}

private fun TypedSymbol.hasBindingOwner(): Boolean =
    ownerPlugin == "credentials-binding" && ownerEvidence != OwnerEvidence.UNKNOWN
