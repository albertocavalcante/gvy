// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import arrow.core.getOrElse
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.resolution.ClasspathResolutionStrategy
import com.github.albertocavalcante.gvy.gls.providers.definition.resolution.ResolutionContext
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import com.github.albertocavalcante.nativeapi.SourceAnnotationReference
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.io.StringReaderSource
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/** Bound types follow their compiler-selected source or binary; workspace name indexes cannot replace either. */
internal class BoundTypeDefinitionResolver(
    private val modules: List<ModuleNode>?,
    private val annotations: SourceAnnotationIndex,
    private val compilation: GroovyCompilationService?,
    private val navigator: SourceNavigator?,
) {
    suspend fun annotation(
        reference: SourceAnnotationReference,
        uri: URI,
        position: Position,
    ): DefinitionResolver.DefinitionResult? {
        val name = reference.binaryName ?: return null
        val sourceName = reference.sourceUnitName
        if (sourceName != null) {
            val module = modules.orEmpty().filter { candidate -> candidate.context?.name == sourceName }
                .singleOrNull() ?: return null
            val declaration = declarations(module).singleOrNull { candidate -> candidate.name == name } ?: return null
            return sourceDefinition(declaration, module, reference.sourceUri)
        }
        return binary(ClassHelper.makeWithoutCaching(name), uri, position)
    }

    suspend fun importDefinition(node: ImportNode, uri: URI, position: Position): DefinitionResolver.DefinitionResult? {
        val selected = node.type?.redirect() ?: return null
        val module = selected.module
        if (isStubSourceName(module?.context?.name)) return null
        if (node.isStatic && node.fieldName != null) {
            annotations.annotationImportedBy(node)?.let { return annotation(it, uri, position) }
            if (selected.isPrimaryClassNode) return null
        }
        if (selected.isPrimaryClassNode && module != null) {
            if (modules != null && modules.none { it === module }) return null
            if (declarations(module).none { it === selected }) return null
            return sourceDefinition(selected, module)
        }
        if (!selected.isResolved) return null
        return binary(node, uri, position)
    }

    private fun sourceDefinition(
        node: ClassNode,
        module: ModuleNode,
        selectedUri: URI? = null,
    ): DefinitionResolver.DefinitionResult.Source? {
        if (node.lineNumber <= 0 || node.columnNumber <= 0) return null
        val name = module.context?.name ?: return null
        if (isStubSourceName(name)) return null
        val reader = module.context?.source
        val mapped = annotations.sourceUri(name) ?: SourceAnnotationIndex.fromModule(module).sourceUri(name)
        val fileUri = selectedUri ?: reader
            ?.takeUnless { it is StringReaderSource }
            ?.getURI()
            ?.takeIf { it.scheme == "file" }
        val uri = mapped ?: fileUri?.let(::canonicalFileUri) ?: return null
        return DefinitionResolver.DefinitionResult.Source(node, uri)
    }

    private fun canonicalFileUri(uri: URI): URI =
        if (uri.scheme == "file" && uri.rawAuthority == null && !uri.isOpaque) {
            URI.create(URI("file", "", uri.path, uri.query, uri.fragment).toASCIIString())
        } else {
            uri
        }

    private suspend fun binary(node: ASTNode, uri: URI, position: Position): DefinitionResolver.DefinitionResult? {
        val service = compilation ?: return null
        return ClasspathResolutionStrategy(service, navigator)
            .resolve(ResolutionContext(node, uri, position, requireExactClassName = true)).getOrElse { null }
    }

    private fun declarations(module: ModuleNode): Sequence<ClassNode> = sequence {
        val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
        val pending = ArrayDeque(module.classes)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!seen.add(node)) continue
            yield(node)
            node.innerClasses?.forEach(pending::addLast)
        }
    }
}
