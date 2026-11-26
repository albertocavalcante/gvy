// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.NativeQueryContext
import com.github.albertocavalcante.gvy.gls.compilation.SignatureCompletionRequest
import com.github.albertocavalcante.gvy.gls.project.JenkinsQueryFacts
import com.github.albertocavalcante.gvy.gls.services.ClassInfo
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/** Qualified inputs for the strategy using this particular parsing copy. */
internal class CompletionInputs(val request: SignatureCompletionRequest, val native: NativeQueryContext) {
    private val compilation = request.compilation
    private data class NameSearch(val prefix: String, val limit: Int, val qualified: Boolean)
    private val classNames = mutableMapOf<NameSearch, List<ClassInfo>>()

    suspend fun jenkins(): JenkinsQueryFacts = request.completionJenkins().also { facts ->
        request.observePublication {
            val paths = facts.classpath
            paths == null || native.held.referenceProducer?.hasClasspath(paths) == true
        }
    }

    fun workspaceClasses(query: ClassNameQuery): List<Symbol.Class> {
        val (selection, current) = compilation.symbolIndexingService.captureClasses(query)
        request.observePublication(current)
        val index = compilation.getWorkspaceSymbolIndex()
        val hidden = index?.referenceShadows().orEmpty()
        val owners = selection.sources.filterKeys { it !in hidden }
        request.observeInputs { checkCancelled ->
            index === compilation.getWorkspaceSymbolIndex() && index?.referenceShadows().orEmpty() == hidden &&
                owners.all { (uri, text) -> text.matches(uri, request.source.documents[uri]?.content, checkCancelled) }
        }
        return selection.classes.filter { it.uri !in hidden }
    }

    fun classNames(prefix: String, limit: Int, qualified: Boolean = false): List<ClassInfo> =
        classNames.getOrPut(NameSearch(prefix, limit, qualified)) { readNames(prefix, limit, qualified) }

    private fun readNames(prefix: String, limit: Int, qualified: Boolean): List<ClassInfo> {
        val answer = compilation.classpathService.captureClassNames(prefix, limit, qualified)
        request.observePublication(answer.current)
        if (native.held.referenceProducer?.hasClasspath(answer.paths) != true) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.RequestFailed, "Class index belongs to another classpath", null),
            )
        }
        // The shared index also indexes the server classpath. A class name only answers this request
        // if this exact parser's bytecode-only loader can resolve it.
        return answer.classes.filter { native.reflection.loadClass(it.fullName) != null }
    }
}
