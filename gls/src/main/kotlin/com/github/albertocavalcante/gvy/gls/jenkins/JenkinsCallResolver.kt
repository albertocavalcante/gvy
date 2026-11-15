// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.jenkins

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsPositionContext
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsRegion
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsSourceContext
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedDeclarativeOption
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.DirectiveEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.SectionEnrichment
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.eclipse.lsp4j.Position
import java.net.URI

enum class JenkinsCallKind { STEP, DIRECTIVE_ENTRY, OPTION, SECTION, DIRECTIVE, LIBRARY_GLOBAL, LOCAL, UNKNOWN }

data class JenkinsCallResolution(
    val kind: JenkinsCallKind,
    val step: MergedStepMetadata? = null,
    val option: MergedDeclarativeOption? = null,
    val section: SectionEnrichment? = null,
    val directive: DirectiveEnrichment? = null,
) {
    val parameters: Map<String, MergedParameter>
        get() = step?.namedParams ?: option?.parameters ?: emptyMap()
}

private enum class Receiver { SCRIPT, STEPS, OTHER }

/** Native bindings and coordinate facts from one document capture. */
data class JenkinsCallContext(
    val astModel: GroovyAstModel?,
    val documentUri: URI,
    val sourceContext: JenkinsSourceContext,
    val sourceIndex: TextIndex = TextIndex(sourceContext.source),
)

object JenkinsCallResolver {
    /** True only when this document's call dispatches through an unshadowed script instance. */
    fun hasScriptDispatch(
        call: MethodCallExpression,
        model: GroovyAstModel,
        uri: URI,
        sourceIndex: TextIndex,
    ): Boolean {
        if (model.getUri(call) != uri) return false
        val receiver = call.objectExpression as? VariableExpression ?: return false
        val name = call.methodAsString ?: return false
        return receiver.isThisExpression && model.hasScriptInstance(call) &&
            !isShadowed(name, call, model, uri, sourceIndex)
    }

    fun resolve(
        call: MethodCallExpression,
        source: JenkinsCallContext,
        metadata: MergedJenkinsMetadata,
        varsNames: Set<String> = emptySet(),
    ): JenkinsCallResolution {
        val name = call.methodAsString
        val receiver = call.receiver(varsNames)
        val model = source.astModel
        val position = call.sourcePosition(source.sourceIndex)
        return when {
            name == null -> JenkinsCallResolution(JenkinsCallKind.UNKNOWN)
            receiver == Receiver.OTHER -> JenkinsCallResolution(JenkinsCallKind.LOCAL)
            // Source text alone cannot establish bindings or distinguish local declarations.
            model == null || model.getUri(call) != source.documentUri -> JenkinsCallResolution(JenkinsCallKind.UNKNOWN)
            !model.hasScriptInstance(call) -> JenkinsCallResolution(JenkinsCallKind.LOCAL)
            position == null -> JenkinsCallResolution(JenkinsCallKind.UNKNOWN)
            else -> resolveInContext(
                ResolvedCall(
                    call,
                    model,
                    name,
                    receiver,
                    positionContext(call, source, position),
                    receiver == Receiver.SCRIPT &&
                        !hasScriptDispatch(call, model, source.documentUri, source.sourceIndex),
                ),
                metadata,
                varsNames,
            )
        }
    }

    private data class ResolvedCall(
        val call: MethodCallExpression,
        val model: GroovyAstModel,
        val name: String,
        val receiver: Receiver,
        val context: JenkinsPositionContext,
        val hasLocalBinding: Boolean,
    )

    private fun resolveInContext(
        site: ResolvedCall,
        metadata: MergedJenkinsMetadata,
        varsNames: Set<String>,
    ): JenkinsCallResolution {
        val call = site.call
        val model = site.model
        val name = site.name
        val receiver = site.receiver
        val context = site.context
        val parent = context.enclosingBlocks.lastOrNull()
        val direct = parent == null || isDirectStatement(call, parent, model)
        val declarative = receiver == Receiver.SCRIPT && context.isDeclarativeCall(name) && direct
        return when {
            // Jenkins parses confirmed Declarative entries as syntax, independently of script methods.
            // The root pipeline call and executable bodies still use ordinary Groovy dispatch.
            declarative && name != "pipeline" -> resolveDeclarative(name, parent, metadata)
            site.hasLocalBinding -> JenkinsCallResolution(JenkinsCallKind.LOCAL)
            context.region == JenkinsRegion.UNCERTAIN -> JenkinsCallResolution(JenkinsCallKind.UNKNOWN)
            declarative -> resolveDeclarative(name, parent, metadata)
            receiver == Receiver.SCRIPT && name in varsNames -> JenkinsCallResolution(JenkinsCallKind.LIBRARY_GLOBAL)
            else -> metadata.steps[name]?.let { JenkinsCallResolution(JenkinsCallKind.STEP, step = it) }
                ?: JenkinsCallResolution(JenkinsCallKind.UNKNOWN)
        }
    }

    private fun JenkinsPositionContext.isDeclarativeCall(name: String): Boolean =
        region == JenkinsRegion.DECLARATIVE_STRUCTURE || (name == "pipeline" && enclosingBlocks.isEmpty())

    private fun resolveDeclarative(
        name: String,
        parent: String?,
        metadata: MergedJenkinsMetadata,
    ): JenkinsCallResolution {
        val entry = parent?.let { metadata.directiveEntries[it]?.get(name) }
        val option = metadata.declarativeOptions[name].takeIf { parent == "options" }
        val enclosing = parent ?: "root"
        val section = metadata.sections[name]?.takeIf { enclosing in it.allowedIn }
        val directive = metadata.directives[name]?.takeIf { enclosing in it.allowedIn }
        return when {
            entry != null -> JenkinsCallResolution(JenkinsCallKind.DIRECTIVE_ENTRY, option = entry)
            option != null -> JenkinsCallResolution(JenkinsCallKind.OPTION, option = option)
            section != null -> JenkinsCallResolution(JenkinsCallKind.SECTION, section = section)
            directive != null -> JenkinsCallResolution(JenkinsCallKind.DIRECTIVE, directive = directive)
            else -> JenkinsCallResolution(JenkinsCallKind.UNKNOWN)
        }
    }

    private fun isDirectStatement(call: MethodCallExpression, parent: String, model: GroovyAstModel): Boolean {
        val statement = model.getParent(call) as? ExpressionStatement ?: return false
        val block = model.getParent(statement) as? BlockStatement ?: return false
        val closure = model.getParent(block) as? ClosureExpression ?: return false
        val owner = generateSequence(model.getParent(closure)) { model.getParent(it) }
            .firstOrNull { it is MethodCallExpression } as? MethodCallExpression
        return owner?.methodAsString == parent
    }

    /** Binds lexical structure to the actual enclosing pipeline call in this document. */
    fun positionContext(node: ASTNode?, source: JenkinsCallContext, position: Position): JenkinsPositionContext {
        val context = source.sourceContext.at(position.line, position.character)
        if (context.enclosingBlocks.firstOrNull() != "pipeline") return context
        val model = source.astModel
        val nativePosition = source.sourceIndex.codePointPosition(position)
        val owner = if (model == null || nativePosition == null) {
            null
        } else {
            pipelineOwner(node, model, source.documentUri, nativePosition)
        }
        return when {
            owner == null || model == null ->
                context.copy(region = JenkinsRegion.UNCERTAIN, allowsGroovyStatements = false)
            model.getUri(owner) != source.documentUri ->
                context.copy(region = JenkinsRegion.UNCERTAIN, allowsGroovyStatements = false)
            owner.isOrdinaryPipelineOwner(model, source.documentUri, source.sourceIndex) ->
                context.copy(region = JenkinsRegion.EXECUTABLE_GROOVY, allowsGroovyStatements = true)
            context.region == JenkinsRegion.UNCERTAIN && node != null && insideValueClosure(node, model) ->
                context.copy(region = JenkinsRegion.EXECUTABLE_GROOVY, allowsGroovyStatements = true)
            else -> context
        }
    }

    private fun pipelineOwner(
        node: ASTNode?,
        model: GroovyAstModel,
        uri: URI,
        position: Position,
    ): MethodCallExpression? {
        val ancestor = node?.let { start ->
            generateSequence(start) { model.getParent(it) }.filterIsInstance<MethodCallExpression>()
                .firstOrNull { it.isPipelineCall() }
        }
        return ancestor ?: model.getNodes(uri).filterIsInstance<MethodCallExpression>().firstOrNull {
            it.isPipelineCall() && it.containsPosition(position.line, position.character)
        }
    }

    private fun MethodCallExpression.isOrdinaryPipelineOwner(
        model: GroovyAstModel,
        uri: URI,
        sourceIndex: TextIndex,
    ): Boolean {
        val receiver = objectExpression as? VariableExpression
        if (!model.hasScriptInstance(this)) return true
        if (methodAsString == "call" && receiver?.name == "pipeline") return true
        val scriptReceiver = isImplicitThis || receiver?.isThisExpression == true
        return !scriptReceiver || isShadowed("pipeline", this, model, uri, sourceIndex)
    }

    private fun insideValueClosure(node: ASTNode, model: GroovyAstModel): Boolean {
        val ancestors = generateSequence(model.getParent(node)) { model.getParent(it) }.toList()
        val closureIndex = ancestors.indexOfFirst { it is ClosureExpression }
        if (closureIndex < 0) return false
        return when (
            val owner = ancestors.drop(closureIndex + 1).firstOrNull {
                it is MapEntryExpression || it is MethodCallExpression
            }
        ) {
            is MapEntryExpression -> true
            is MethodCallExpression -> {
                val receiver = owner.objectExpression as? VariableExpression
                owner.isImplicitThis.not() && receiver?.isThisExpression != true
            }
            else -> false
        }
    }

    private fun isShadowed(
        name: String,
        call: MethodCallExpression,
        model: GroovyAstModel,
        uri: URI,
        sourceIndex: TextIndex,
    ): Boolean {
        val parents = generateSequence(model.getParent(call)) { model.getParent(it) }.toList()
        val position = call.sourcePosition(sourceIndex) ?: return false
        return JenkinsVisibleVariables.find(model, uri, call, position, sourceIndex).any { it.name == name } ||
            parents.any { it.declaresLexically(name) } || isClassMember(name, parents, model, uri)
    }

    private fun isClassMember(name: String, parents: List<ASTNode>, model: GroovyAstModel, uri: URI): Boolean {
        val enclosingClass = parents.firstNotNullOfOrNull { parent ->
            when (parent) {
                is ClassNode -> parent
                is MethodNode -> parent.declaringClass
                else -> null
            }
        }
        if (enclosingClass != null && !enclosingClass.isScript) return true
        val classes = parents.filterIsInstance<ClassNode>().ifEmpty {
            model.getAllClassNodes().filter {
                it.isScript &&
                    model.getUri(it).let { owner -> owner == null || owner == uri }
            }
        }
        return classes.any { cls ->
            cls.getDeclaredMethods(name).isNotEmpty() || cls.getDeclaredField(name) != null ||
                cls.getProperty(name) != null
        }
    }
}

private fun MethodCallExpression.receiver(varsNames: Set<String>): Receiver {
    val expression = objectExpression as? VariableExpression ?: return Receiver.OTHER
    if (expression.isThisExpression) return Receiver.SCRIPT
    if (expression.name != "steps" || "steps" in varsNames) return Receiver.OTHER
    val variable = expression.accessedVariable
    return if (variable == null || variable is DynamicVariable) Receiver.STEPS else Receiver.OTHER
}

private fun MethodCallExpression.sourcePosition(index: TextIndex): Position? {
    if (lineNumber < 1 || columnNumber < 1) return null
    return index.utf16Position(Position(lineNumber - 1, columnNumber - 1))
}

private fun MethodCallExpression.isPipelineCall(): Boolean = methodAsString == "pipeline" ||
    (methodAsString == "call" && (objectExpression as? VariableExpression)?.name == "pipeline")

private fun ASTNode.containsPosition(line: Int, character: Int): Boolean {
    if (minOf(lineNumber, columnNumber, lastLineNumber, lastColumnNumber) < 1) return false
    val firstLine = lineNumber - 1
    val lastLine = lastLineNumber - 1
    if (line !in firstLine..lastLine) return false
    if (line == firstLine && character < columnNumber - 1) return false
    return line != lastLine || character < lastColumnNumber - 1
}

private fun ASTNode.declaresLexically(name: String): Boolean = when (this) {
    is ClosureExpression -> parameters?.any { it.name == name } == true ||
        variableScope?.getDeclaredVariable(name) != null
    is MethodNode -> parameters.any { it.name == name } || variableScope?.getDeclaredVariable(name) != null
    else -> false
}

private fun GroovyAstModel.hasScriptInstance(call: MethodCallExpression): Boolean =
    generateSequence(getParent(call)) { getParent(it) }.firstNotNullOfOrNull { parent ->
        when (parent) {
            is MethodNode -> !parent.isStatic && parent.declaringClass?.isScript != false
            is ClassNode -> parent.isScript
            else -> null
        }
    } ?: true
