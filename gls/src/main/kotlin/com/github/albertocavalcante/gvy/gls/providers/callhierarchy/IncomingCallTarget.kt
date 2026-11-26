// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.callhierarchy

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.io.StringReaderSource
import org.codehaus.groovy.transform.stc.StaticTypesMarker
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** An encoded method ID can denote both a source declaration and its namesake in a binary. */
internal class IncomingCallTarget(private val uri: URI, private val declaration: ASTNode, visibleDocuments: Set<URI>) {
    // Groovy names an in-memory companion by its URI path. Distinct URIs can collapse to that same name.
    private val sourceName = uri.path ?: uri.toString()
    private val uniqueInputName = visibleDocuments.none { it != uri && (it.path ?: it.toString()) == sourceName }

    private val owner = when (declaration) {
        is ClassNode -> declaration
        is MethodNode -> declaration.declaringClass
        else -> null
    }
    private val methodId = (declaration as? MethodNode)?.let {
        SemanticDocumentBuilder.createMethodSymbolId(it.declaringClass, it).id
    }

    /** Dispatch analysis in the index admitted these method tokens for this symbol. */
    fun methodSites(document: SemanticDocument): Set<Position> = methodId?.let { symbol ->
        document.findOccurrences(symbol).asSequence().filter { it.role == OccurrenceRole.CALL }
            .map { Position(it.range.startLine, it.range.startColumn) }.toSet()
    }.orEmpty()

    /** Class references also include imports, literals and the types named in class headers. */
    fun isNonCallTypeUse(site: ASTNode, model: GroovyAstModel): Boolean {
        if (declaration !is ClassNode) return false
        return when (site) {
            is ClassExpression -> true
            is ClassNode -> when (val parent = model.getParent(site)) {
                is ImportNode -> true
                is ClassNode -> parent.unresolvedSuperClass === site || parent.unresolvedInterfaces.any { it === site }
                else -> false
            }
            // Inside annotated imports only the tracked imported type itself is a proven non-call.
            is ImportNode -> site.annotations.isEmpty()
            else -> false
        }
    }

    fun requireBinding(site: ASTNode, enclosing: ASTNode, model: GroovyAstModel, sites: Set<Position>) {
        val matches = when (val call = callAt(site, model)) {
            is MethodCallExpression -> matchesMethod(call, enclosing, model, sites)
            is StaticMethodCallExpression ->
                matchesDeclaration(call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET))
            is ConstructorCallExpression -> matchesConstructor(call, sites)
            else -> false
        }
        if (!matches) unavailable()
    }

    private fun callAt(site: ASTNode, model: GroovyAstModel): ASTNode? {
        val parent = model.getParent(site)
        return when {
            site is MethodCallExpression -> site
            site is StaticMethodCallExpression -> site
            site is ConstructorCallExpression -> site
            parent is MethodCallExpression && isMethodToken(parent, site) -> parent
            parent is ConstructorCallExpression && parent.type === site -> parent
            else -> null
        }
    }

    private fun matchesConstructor(call: ConstructorCallExpression, sites: Set<Position>): Boolean {
        val type = call.type.redirect()
        if (!matchesOwner(type)) return false
        if (declaration is ClassNode) return true
        if (declaration !is ConstructorNode || call.isUsingAnonymousInnerClass || call.isSpecialCall) return false
        // The constructor CALL spans from 'new' through the type. The class CALL starts at the type itself.
        if (Position(call.lineNumber - 1, call.columnNumber - 1) !in sites) return false
        val constructor = type.declaredConstructors.singleOrNull {
            SemanticDocumentBuilder.createMethodSymbolId(type, it).id == methodId
        }
        return matchesDeclaration(constructor)
    }

    private fun matchesMethod(
        call: MethodCallExpression,
        enclosing: ASTNode,
        model: GroovyAstModel,
        sites: Set<Position>,
    ): Boolean {
        val direct = call.methodTarget ?: call.getNodeMetaData<MethodNode>(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        if (direct != null) return matchesDeclaration(direct)
        val method = receiverClass(call, enclosing)?.takeIf(::matchesOwner)
            ?.getDeclaredMethods(call.methodAsString)?.singleOrNull() ?: return false
        val token = call.method
        val indexed = Position(token.lineNumber - 1, token.columnNumber - 1) in sites
        return if (indexed) {
            matchesDeclaration(method)
        } else {
            scriptMethod(call, method, enclosing, model)
        }
    }

    private fun receiverClass(call: MethodCallExpression, enclosing: ASTNode): ClassNode? {
        val receiver = call.objectExpression
        return when {
            call.isImplicitThis || receiver is VariableExpression && receiver.isThisExpression -> when (enclosing) {
                is MethodNode -> enclosing.declaringClass
                is ClassNode -> enclosing
                else -> null
            }
            receiver is VariableExpression -> receiver.accessedVariable?.type
            receiver is ClassExpression -> receiver.type
            receiver is ConstructorCallExpression -> receiver.type.takeUnless { receiver.isUsingAnonymousInnerClass }
            else -> null
        }?.redirect()
    }

    /** The local AST scan can find script methods for which the semantic document has no CALL occurrence. */
    private fun scriptMethod(
        call: MethodCallExpression,
        method: MethodNode,
        enclosing: ASTNode,
        model: GroovyAstModel,
    ): Boolean {
        val script = method.declaringClass
        val lexicalOwner = (enclosing as? MethodNode)?.declaringClass
        val implicitScriptCall = call.isImplicitThis && script.isScript && lexicalOwner === script
        // Prove the exact source MethodNode in this same parse, with no competing overload (including inherited).
        if (!implicitScriptCall || method !== declaration || model.getUri(method) != uri) return false
        val arguments = (call.arguments as? TupleExpression)?.expressions ?: return false
        val exactArity = arguments.size == method.parameters.size && arguments.none { it is SpreadExpression }
        return exactArity && script.getMethods(method.name).singleOrNull() === method &&
            outsideDelegatingClosure(call, enclosing, model)
    }

    private fun outsideDelegatingClosure(
        call: MethodCallExpression,
        enclosing: ASTNode,
        model: GroovyAstModel,
    ): Boolean {
        var node: ASTNode? = call
        while (node != null && node !== enclosing && node !is ClosureExpression) {
            node = model.getParent(node)
        }
        return node === enclosing
    }

    private fun matchesDeclaration(method: MethodNode?): Boolean {
        val target = declaration as? MethodNode ?: return false
        return method != null && matchesOwner(method.declaringClass) && method.name == target.name &&
            method.lineNumber > 0 && method.lineNumber == target.lineNumber &&
            method.columnNumber == target.columnNumber
    }

    private fun matchesOwner(candidate: ClassNode): Boolean {
        val target = owner ?: return false
        if (!candidate.isPrimaryClassNode || candidate.name != target.name) return false
        if (candidate === target) return true
        val unit = candidate.module?.context ?: return false
        val reader = unit.source ?: return false
        return if (reader is StringReaderSource) {
            uniqueInputName && unit.name == sourceName
        } else {
            reader.getURI() == uri
        }
    }

    companion object {
        fun unavailable(): Nothing = throw ResponseErrorException(
            ResponseError(
                ResponseErrorCode.RequestFailed,
                "Cannot establish the source declaration of an incoming call",
                null,
            ),
        )
    }
}

/** Native position lookup can choose the implicit receiver placed at the very same token as the name. */
internal fun isMethodToken(call: MethodCallExpression, site: ASTNode): Boolean = call.method === site ||
    (
        call.isImplicitThis && call.objectExpression === site &&
            site.lineNumber == call.method.lineNumber && site.columnNumber == call.method.columnNumber
    )
