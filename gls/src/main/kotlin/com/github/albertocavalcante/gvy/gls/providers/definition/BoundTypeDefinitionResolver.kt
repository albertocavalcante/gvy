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
import org.codehaus.groovy.ast.FieldNode
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
    private data class ImportedMemberCandidate(val owner: ClassNode, val member: ASTNode)

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
            return declarations(module).singleOrNull { candidate ->
                candidate.name == name
            }?.let { sourceDefinition(it, module, reference.sourceUri) }
        }
        return binary(ClassHelper.makeWithoutCaching(name), uri, position)
    }

    suspend fun importDefinition(node: ImportNode, uri: URI, position: Position): DefinitionResolver.DefinitionResult? {
        val selected = node.type?.redirect()?.takeUnless { isStubSourceName(it.module?.context?.name) } ?: return null
        if (node.isStatic && node.fieldName != null) return staticImportDefinition(node, selected, uri, position)
        val module = selected.module
        return when {
            selected.isPrimaryClassNode && module != null -> importedSourceDefinition(selected, module)
            selected.isPrimaryClassNode || !selected.isResolved -> null
            else -> binary(node, uri, position)
        }
    }

    private suspend fun staticImportDefinition(
        node: ImportNode,
        selected: ClassNode,
        uri: URI,
        position: Position,
    ): DefinitionResolver.DefinitionResult? {
        val name = checkNotNull(node.fieldName)
        val annotation = annotations.annotationImportedBy(node)
        return if (annotation == null) {
            importedMemberDefinition(selected, name, uri, position)
        } else {
            annotation(annotation, uri, position)
        }
    }

    private fun importedSourceDefinition(
        selected: ClassNode,
        module: ModuleNode,
    ): DefinitionResolver.DefinitionResult? {
        if (modules != null && modules.none { it === module }) return null
        if (declarations(module).none { it === selected }) return null
        return sourceDefinition(selected, module)
    }

    private suspend fun importedMemberDefinition(
        selected: ClassNode,
        name: String,
        uri: URI,
        position: Position,
    ): DefinitionResolver.DefinitionResult? {
        val candidate = findImportedMember(selected, name) ?: return null
        return resolveImportedMember(candidate, name, uri, position)
    }

    private fun findImportedMember(selected: ClassNode, name: String): ImportedMemberCandidate? {
        val seen = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
        val pending = ArrayDeque<ClassNode>()
        pending.add(selected)
        while (pending.isNotEmpty()) {
            val candidates = collectImportedMemberCandidates(selected, name, pending, seen)
            if (candidates.isNotEmpty()) return candidates.singleOrNull()
        }
        return null
    }

    private fun collectImportedMemberCandidates(
        selected: ClassNode,
        name: String,
        pending: ArrayDeque<ClassNode>,
        seen: MutableSet<ClassNode>,
    ): List<ImportedMemberCandidate> {
        val candidates = mutableListOf<ImportedMemberCandidate>()
        repeat(pending.size) {
            val owner = pending.removeFirst().redirect()
            if (seen.add(owner)) {
                candidates += staticFields(owner, name, inherited = owner !== selected)
                candidates += staticMethods(owner, name, inherited = owner !== selected)
                    .map { ImportedMemberCandidate(owner, it) }
                enqueueParentsWithoutDeclaration(owner, name, pending)
            }
        }
        return candidates
    }

    private fun staticFields(owner: ClassNode, name: String, inherited: Boolean): List<ImportedMemberCandidate> =
        owner.fields.filter { field ->
            field.name == name && field.isStatic &&
                isVisibleInheritedField(owner, field, name, inherited)
        }.map { ImportedMemberCandidate(owner, it) }

    private fun isVisibleInheritedField(
        owner: ClassNode,
        field: FieldNode,
        name: String,
        inherited: Boolean,
    ): Boolean {
        if (!inherited) return true
        if (!field.isPrivate) return true
        return owner.properties.any { it.name == name && !it.isPrivate }
    }

    private fun staticMethods(owner: ClassNode, name: String, inherited: Boolean) = owner.methods.filter { method ->
        when {
            method.name != name -> false
            !method.isStatic -> false
            !inherited -> true
            else -> !method.isPrivate && !owner.isInterface
        }
    }

    /** A nearer declaration hides a same-named parent member even when it is inaccessible or non-static. */
    private fun enqueueParentsWithoutDeclaration(owner: ClassNode, name: String, pending: ArrayDeque<ClassNode>) {
        val declaresName = owner.fields.any { it.name == name } || owner.methods.any { it.name == name }
        if (!declaresName) {
            owner.superClass?.let(pending::addLast)
            owner.interfaces.forEach(pending::addLast)
        }
    }

    private suspend fun resolveImportedMember(
        candidate: ImportedMemberCandidate,
        name: String,
        uri: URI,
        position: Position,
    ): DefinitionResolver.DefinitionResult? {
        val owner = candidate.owner
        val module = owner.module
        if (isStubSourceName(module?.context?.name)) return null
        return when {
            owner.isPrimaryClassNode -> importedSourceMemberDefinition(owner, candidate.member, module)
            owner.isResolved -> binary(ImportNode(owner, name, name), uri, position)
            else -> null
        }
    }

    private fun importedSourceMemberDefinition(
        owner: ClassNode,
        member: ASTNode,
        module: ModuleNode?,
    ): DefinitionResolver.DefinitionResult.Source? {
        val sourceModule = module?.takeIf { isSelectedModule(it) } ?: return null
        if (declarations(sourceModule).none { it === owner }) return null
        return sourceDefinition(member, sourceModule)
    }

    private fun isSelectedModule(module: ModuleNode): Boolean = modules == null || modules.any { it === module }

    private fun sourceDefinition(
        node: ASTNode,
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
