// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.ast.visitor.isSharedDefaultType
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.semantics.sourceSimpleName
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/**
 * The complete edit of a class rename in the document that declares the class: every token that spells the class
 * name is accounted for (see [ClassUseCoverage]), or the rename is refused with [RenameRefusedException].
 */
internal object ClassRenameUses {
    /**
     * The edits renaming [type], declared in [compiled]'s module, makes in [documentUri]: [located] (what the reference
     * scan and the constructor reader found) plus every other use the AST positions, each name once.
     */
    fun covering(
        type: ClassNode,
        compiled: CompiledDocumentSnapshot,
        documentUri: URI,
        located: List<Location>,
        name: String,
    ): List<Location> {
        val module = compiled.parseResult.ast
        val tokens = compiled.parseResult.tokenIndex
        val source = TextIndex(compiled.sourceText)
        val occurrences = tokens?.nameOccurrences(compiled.sourceText, name)
        if (module == null || tokens == null || occurrences == null) {
            refuse("Cannot rename '$name': the source cannot be read as complete tokens")
        }
        if (located.any { URI.create(it.uri) != documentUri }) {
            refuse("Cannot rename '$name': an edit it found is outside the document being renamed")
        }
        val account = DocumentNames(module, source, tokens).account(
            type,
            name,
            occurrences.map { NameSpan(it.start, it.end) },
            located.map { NameSpan(source.offsetOf(it.range.start), source.offsetOf(it.range.end)) },
        )
        return when (val uses = ClassUseCoverage.check(account) { source.positionOf(it).line + 1 }) {
            is ClassUses.Complete -> uses.edits.map {
                Location(documentUri.toString(), Range(source.positionOf(it.start), source.positionOf(it.end)))
            }
            is ClassUses.Refused -> refuse(uses.reason)
        }
    }

    /**
     * The class declared in [compiled]'s module that a type use spells at UTF-16 [offset] when the code token there is
     * that class's name (the innermost use: `Service` in `List<Service>`); null anywhere else.
     */
    fun classNamedAt(compiled: CompiledDocumentSnapshot, offset: Int): ClassNode? {
        val module = compiled.parseResult.ast ?: return null
        val tokens = compiled.parseResult.tokenIndex ?: return null
        val source = TextIndex(compiled.sourceText)
        return DocumentNames(module, source, tokens).usesAt(offset)
            .filter { declaredIn(it.type, compiled) }
            .firstOrNull { site ->
                tokens.nameOccurrences(compiled.sourceText, site.type.sourceSimpleName()).orEmpty()
                    .any { it.start <= offset && offset < it.end }
            }?.type
    }

    /** Whether [type] (any node of it) is a class [compiled]'s own module declares. */
    fun declaredIn(type: ClassNode, compiled: CompiledDocumentSnapshot): Boolean {
        val declared = type.redirect()
        return compiled.parseResult.ast?.classes.orEmpty().any { it === declared }
    }

    private fun refuse(message: String): Nothing =
        throw RenameRefusedException(ResponseError(ResponseErrorCode.RequestFailed, message, null))
}

/** Where the AST places names in one module's source, as windows of UTF-16 offsets. */
private class DocumentNames(
    private val module: ModuleNode,
    private val source: TextIndex,
    private val tokens: GroovyTokenIndex,
) {
    private val sites = TypeUseSites.of(module)

    fun account(type: ClassNode, name: String, occurrences: List<NameSpan>, located: List<NameSpan>): NameAccount {
        val sameNamed = module.classes.filter { it !== type && it.sourceSimpleName() == name }
        val otherUses = sites.uses.filter { it.type !== type && it.type.sourceSimpleName() == name }
        val otherNames = sites.otherNames.filter { it.name == name }
        val others = otherNames.mapNotNull { single(occurrences, otherNameWindow(it)) } +
            sameNamed.flatMap { declaredNames(it, name, occurrences) }
        return NameAccount(
            name,
            occurrences,
            located,
            uses = sites.uses.filter { it.type === type }.mapNotNull(::window),
            others = others,
            otherUses = otherUses.mapNotNull(::window),
        )
    }

    /** The use sites whose window contains [offset], innermost first. */
    fun usesAt(offset: Int): List<TypeUseSite> = sites.uses
        .mapNotNull { site -> window(site)?.takeIf { it.start <= offset && offset < it.end }?.let { site to it } }
        .sortedBy { (_, window) -> window.size }
        .map { (site, _) -> site }

    private fun window(site: TypeUseSite): NameWindow? {
        val start = start(site.node) ?: return null
        val end = end(site.node) ?: return null
        return NameWindow(start, end, site.kind, source.positionOf(start).line + 1).takeIf { start < end }
    }

    /** The part of a name site's node after its [OtherNameSite.after] nodes and before its [OtherNameSite.until]. */
    private fun otherNameWindow(site: OtherNameSite): NameSpan? {
        val nodeStart = start(site.node) ?: return null
        val nodeEnd = end(site.node) ?: return null
        val start = (site.after.filterNot(::isSharedDefaultType).mapNotNull(::end) + nodeStart).max()
        val end = (site.until.mapNotNull(::start) + nodeEnd).min()
        return NameSpan(start, end).takeIf { start < end }
    }

    /**
     * The declared name and constructor names of a same-named class: its name is the first occurrence after its
     * annotations, as only modifiers and the declaring keyword come before it.
     */
    private fun declaredNames(other: ClassNode, name: String, occurrences: List<NameSpan>): List<NameSpan> {
        val classStart = start(other)
        val classEnd = end(other)
        val declaration = if (classStart == null || classEnd == null) {
            null
        } else {
            val from = (other.annotations.mapNotNull(::end) + classStart).max()
            occurrences.firstOrNull { it.start >= from && it.end <= classEnd }
        }
        val constructors = when (val located = ConstructorNameReader.locate(other, name, source, tokens)) {
            is ConstructorNames.Located -> located.ranges.map {
                NameSpan(source.offsetOf(it.start), source.offsetOf(it.end))
            }
            is ConstructorNames.Unlocatable, is ConstructorNames.Compact -> emptyList()
        }
        return listOfNotNull(declaration) + constructors
    }

    private fun single(occurrences: List<NameSpan>, window: NameSpan?): NameSpan? =
        window?.let { occurrences.singleOrNull { span -> span.start >= it.start && span.end <= it.end } }

    private fun start(node: ASTNode): Int? = range(node)?.let { source.offsetOf(it.start) }

    private fun end(node: ASTNode): Int? = range(node)?.let { source.offsetOf(it.end) }

    private fun range(node: ASTNode): Range? = node.toLspRange()?.let(source::utf16Range)
}
