// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.engine.impl.native.features

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.HoverMemberLookup
import com.github.albertocavalcante.gvy.gls.compilation.PreparedFeature
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.compilation.inputsCurrent
import com.github.albertocavalcante.gvy.gls.engine.api.HoverProvider
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.providers.hover.HeldHoverProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.gvy.gls.providers.hover.MethodCallMetadataResolver
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.JvmClasspathReflection
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.gvy.gls.services.heldGdkMethods
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.sources.documentationForSelectedBinary
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverProvider as DelegateHoverProvider

/**
 * Native hover provider that delegates to the existing HoverProvider implementation.
 * This ensures all existing hover logic (including Jenkins-specific handling) continues to work.
 *
 * @param parseResult The parse result retained with the session's source for snapshot-aware requests
 */
class NativeHoverProvider(
    private val parseResult: ParseResult,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator? = null,
) : HoverProvider {

    private val logger = KotlinLogging.logger {}

    private val delegate by lazy {
        val resolver = SemanticTypeResolver(
            compilationService.classpathService.getTypeSolver(),
            compilationService.getWorkspaceSymbolIndex(),
        )
        val methods = MethodCallMetadataResolver(
            compilationService.classpathService,
            compilationService.gdkProvider,
            resolver,
        )
        DelegateHoverProvider(
            compilationService,
            documentProvider,
            HoverContentGenerator(resolver, methods),
            sourceNavigator,
        )
    }

    internal fun forSnapshot(
        held: CompiledDocumentSnapshot,
        source: SnapshotSource,
        jenkins: JenkinsQuerySnapshot,
    ): HoverProvider = object : HeldHoverProvider {
        override suspend fun prepareHover(params: HoverParams): PreparedFeature<Hover> {
            val facts = jenkins.materialize(forHover = true)
            require(held.parseResult === parseResult)
            val loader = bytecodeOnlyClassLoader(parseResult.compilationUnit.classLoader)
            val reflection = JvmClasspathReflection { loader }
            val members = HoverMemberLookup.forRequest(held) { compilationService.referenceReadiness(source) }
            val types = SemanticTypeResolver(ReflectionTypeSolver(jreOnly = false, classLoader = loader), members)
            val methods = MethodCallMetadataResolver(
                reflection::getMethods,
                reflection::loadClass,
                { receiver, name -> heldGdkMethods(reflection, receiver, name) },
                types,
            )
            val provider = DelegateHoverProvider(
                compilationService,
                documentProvider,
                HoverContentGenerator(types, methods),
                facts,
            ) { type ->
                sourceNavigator?.let { navigator ->
                    reflection.loadClass(type.name)?.let { binary ->
                        navigator.documentationForSelectedBinary(binary)
                    }
                }
            }
            return PreparedFeature(
                publicationsCurrent = {
                    val classpath = facts.classpath
                    (classpath == null || held.referenceProducer?.hasClasspath(classpath) == true) &&
                        facts.publicationsCurrent() &&
                        members.isDatabaseCurrent()
                },
                qualify = { currentCoroutineContext().inputsCurrent(members::isCurrent) && facts.isCurrent() },
            ) {
                provider.provideHover(source.uri.toString(), params.position, held, loader.context) ?: emptyHover()
            }
        }
    }

    override suspend fun getHover(params: HoverParams): Hover = runCatching {
        delegate.provideHover(params.textDocument.uri, params.position) ?: emptyHover()
    }
        .onFailure { throwable ->
            when (throwable) {
                is CancellationException -> throw throwable
                is Error -> throw throwable
                else -> logger.error(throwable) { "Error providing hover" }
            }
        }
        .getOrDefault(emptyHover())

    private fun emptyHover() = Hover().apply {
        contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, ""))
    }
}
