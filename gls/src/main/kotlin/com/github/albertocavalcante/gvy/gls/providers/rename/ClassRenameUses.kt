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
     * The edits renaming [type], declared in [compiled]'s module, makes in [documentUri]: [located] plus every other
     * use the AST positions, each name once. [located] is what the reference scan and the constructor reader found:
     * the declaration's name, constructor names, and the references the scan reports, among them every class
     * expression (`Service.NAME`, `Service.class`, `Service::new`, `@Ann(Service)`), whose type node has no position
     * of its own, so no use-site window covers it; the coverage check only verifies each is a name token in code.
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
     * What a rename at UTF-16 [offset] renames when the code token there is the name a type use spells (the innermost
     * use: `Date` in `Inner<java.util.Date>`): the class, whether this module declares it or not, or a refusal for a
     * type parameter (`T`, which redirects to its bound). Null on any other token.
     */
    fun classAt(compiled: CompiledDocumentSnapshot, offset: Int): ClassAtCursor? {
        val module = compiled.parseResult.ast ?: return null
        val tokens = compiled.parseResult.tokenIndex ?: return null
        val names = DocumentNames(module, TextIndex(compiled.sourceText), tokens)
        fun spelledAtCursor(name: String) = tokens.nameOccurrences(compiled.sourceText, name).orEmpty()
            .any { it.start <= offset && offset < it.end }
        val parameter = names.typeParametersAt(offset).firstOrNull { spelledAtCursor(it.name) }
        val site = names.usesAt(offset).firstOrNull { spelledAtCursor(it.type.sourceSimpleName()) }
        return when {
            parameter != null -> ClassAtCursor.Refused(
                "Cannot rename '${parameter.name}': renaming a type parameter is not supported yet",
            )
            site == null -> null
            declaredIn(site.type, compiled) -> ClassAtCursor.Declared(site.type)
            else -> ClassAtCursor.Foreign(site.type)
        }
    }

    /** The refusal of a class rename this file cannot complete, as the class is declared in another file. */
    fun declaredElsewhere(name: String): String =
        "Cannot rename '$name': it is not declared in this file, and renaming across files is not supported yet"

    /** Whether [type] (any node of it) is a class [compiled]'s own module declares. */
    fun declaredIn(type: ClassNode, compiled: CompiledDocumentSnapshot): Boolean {
        val declared = type.redirect()
        return compiled.parseResult.ast?.classes.orEmpty().any { it === declared }
    }

    private fun refuse(message: String): Nothing =
        throw RenameRefusedException(ResponseError(ResponseErrorCode.RequestFailed, message, null))
}

/** The class a rename at a cursor on a type use renames, or why it is refused there. */
internal sealed interface ClassAtCursor {
    data class Declared(val type: ClassNode) : ClassAtCursor

    /** A class declared in another file or a dependency, whose rename this file cannot complete. */
    data class Foreign(val type: ClassNode) : ClassAtCursor

    data class Refused(val reason: String) : ClassAtCursor
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

    /** The type parameter sites whose node contains [offset]. */
    fun typeParametersAt(offset: Int): List<TypeParameterSite> = sites.typeParameters.filter { site ->
        val start = start(site.node)
        val end = end(site.node)
        start != null && end != null && start <= offset && offset < end
    }

    /**
     * The part of the source [site] can spell its class in: its node, or for an outer qualifier (`Outer.Inner`) the
     * part before the nested class's own name, as the node also spans the type arguments (`Inner<Outer>`).
     */
    private fun window(site: TypeUseSite): NameWindow? {
        val start = start(site.node) ?: return null
        val nodeEnd = end(site.node) ?: return null
        val end = when (val nested = site.nested) {
            null -> nodeEnd
            else -> tokens.nameOccurrences(source.text, nested.sourceSimpleName()).orEmpty()
                .firstOrNull { it.start >= start && it.end <= nodeEnd }?.start ?: return null
        }
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
