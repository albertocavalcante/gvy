// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI

/** Source occurrences survive collector expansion and are owned by the parse that captured them. */
class SourceAnnotationIndex internal constructor(
    references: Iterable<SourceAnnotationReference>,
    val coverage: AnnotationCoverage,
    compilerImportRanges: Map<Pair<Int, Int>, ParserRange> = emptyMap(),
    declarations: Iterable<SourceAnnotationDeclaration> = emptyList(),
    compilerSourceUris: Map<String, URI?> = emptyMap(),
    compilerImportSelections: Map<Pair<Int, Int>, SourceImportRanges> = emptyMap(),
    compilerForeignDeclarations: Map<String, List<SourceAnnotationDeclaration>> = emptyMap(),
    sourceAnnotatedImports: Iterable<ParserRange> = emptyList(),
) {
    val references: List<SourceAnnotationReference> = references.toPersistentList()
    private val importRanges = compilerImportRanges.toPersistentMap()
    val declarations: List<SourceAnnotationDeclaration> = declarations.toPersistentList()
    private val sourceUris = compilerSourceUris.toPersistentMap()
    private val importSelections = compilerImportSelections.toPersistentMap()
    private val foreignDeclarations = compilerForeignDeclarations
        .mapValues { (_, declarations) -> declarations.toPersistentList() }
        .toPersistentMap()
    private val sourceAnnotatedImports = sourceAnnotatedImports.toPersistentSet()
    private val importedAnnotations = buildMap<ParserRange, SourceAnnotationReference?> {
        for (reference in this@SourceAnnotationIndex.references) {
            if (reference.kind != AnnotationReferenceKind.HEAD) continue
            val range = (reference.importBinding as? AnnotationImportBinding.Explicit)?.range ?: continue
            if (range !in this) {
                put(range, reference)
            } else {
                val previous = get(range)
                if (
                    previous?.binaryName != reference.binaryName ||
                    previous?.sourceUnitName != reference.sourceUnitName ||
                    previous?.sourceUri != reference.sourceUri
                ) {
                    put(range, null)
                }
            }
        }
    }.toPersistentMap()

    fun annotationImportedBy(import: ImportNode): SourceAnnotationReference? =
        importRange(import)?.let(importedAnnotations::get)

    fun foreignDeclaration(sourceUnitName: String, binaryName: String): SourceAnnotationDeclaration? =
        foreignDeclarations[sourceUnitName]?.singleOrNull { it.binaryName == binaryName }

    /** Original compiler input URI, preserving authorities and virtual-document schemes lost by source names. */
    fun sourceUri(sourceUnitName: String): URI? = sourceUris[sourceUnitName]

    private val protectedImports = this.references.flatMap { reference ->
        when (val binding = reference.importBinding) {
            is AnnotationImportBinding.Explicit -> listOf(binding.range)
            is AnnotationImportBinding.Unknown -> binding.candidates
            AnnotationImportBinding.None -> emptyList()
        }
    }.toPersistentSet()

    private val hasUnboundedUncertainty = this.references.any {
        (it.importBinding as? AnnotationImportBinding.Unknown)?.candidates?.isEmpty() == true
    }

    fun headAt(line: Int, character: Int): SourceAnnotationReference? = references.firstOrNull { reference ->
        reference.kind == AnnotationReferenceKind.HEAD &&
            reference.nameSegments.any { range -> range.contains(line, character) }
    }

    fun isHeadNameTriviaAt(line: Int, character: Int): Boolean = references.any { reference ->
        reference.kind == AnnotationReferenceKind.HEAD &&
            reference.nameRange.contains(line, character) &&
            reference.nameSegments.none { range -> range.contains(line, character) }
    }

    /** Rules out annotation use only. Callers must also check ordinary source uses before reporting non-use. */
    fun canRuleOutAnnotationUse(range: ParserRange): Boolean = coverage == AnnotationCoverage.Complete &&
        !hasUnboundedUncertainty &&
        range !in protectedImports

    /** Verified UTF-16 range for an import from the same held parse, never a new lookup by URI. */
    fun importRange(import: ImportNode): ParserRange? = importRanges[import.lineNumber to import.columnNumber]

    fun importSelectionRange(import: ImportNode): ParserRange? =
        importSelections[import.lineNumber to import.columnNumber]?.selection

    /** Nodes are supplied by the same held parse; the index retains only immutable positions. */
    fun importAt(imports: Iterable<ImportNode>, line: Int, character: Int): ImportNode? =
        imports.singleOrNull { import ->
            val statement = importSelections[import.lineNumber to import.columnNumber]?.statement
                ?: importRange(import)?.takeIf { import.annotations.isEmpty() }
            statement?.contains(line, character) == true
        }

    fun canRuleOutAnnotationUse(import: ImportNode): Boolean =
        importRange(import)?.let(::canRuleOutAnnotationUse) ?: false

    /** True when source annotations were attached to this import before collector expansion. */
    fun isSourceAnnotated(import: ImportNode): Boolean =
        importRange(import)?.let(sourceAnnotatedImports::contains) == true

    companion object {
        val UNAVAILABLE = SourceAnnotationIndex(emptyList(), AnnotationCoverage.Unavailable)

        /** Compatibility for callers already holding this exact AST, without a URI/cache lookup. */
        fun fromModule(module: ModuleNode): SourceAnnotationIndex =
            module.getNodeMetaData<SourceAnnotationIndex>(SourceAnnotationIndex::class.java) ?: UNAVAILABLE
    }
}

internal data class SourceImportRanges(val statement: ParserRange, val selection: ParserRange)

@ConsistentCopyVisibility
data class SourceAnnotationReference internal constructor(
    val spelling: String,
    val nameRange: ParserRange,
    val nameSegments: List<ParserRange>,
    val kind: AnnotationReferenceKind,
    /** Lexical source owner, useful without retaining its compiler node. Null for package/import occurrences. */
    val enclosingBinaryName: String?,
    /** Null means the compiler did not bind this occurrence, including policy-stripped annotations. */
    val binaryName: String?,
    val importBinding: AnnotationImportBinding,
    /** The compiler-selected primary source, never a lookup by name in another parse. Null for binaries/unknowns. */
    val sourceUnitName: String? = null,
    val sourceUri: URI? = null,
)

enum class AnnotationReferenceKind { HEAD, ARGUMENT }

@ConsistentCopyVisibility
data class SourceAnnotationDeclaration internal constructor(
    val binaryName: String,
    val declarationRange: ParserRange,
    val nameRange: ParserRange,
)

sealed interface AnnotationImportBinding {
    data class Explicit(val range: ParserRange) : AnnotationImportBinding
    data object None : AnnotationImportBinding

    /** Empty candidates mean uncertainty can affect any explicit import in this source. */
    class Unknown internal constructor(candidates: Iterable<ParserRange>) : AnnotationImportBinding {
        val candidates: Set<ParserRange> = candidates.toPersistentSet()
    }
}

sealed interface AnnotationCoverage {
    data object Complete : AnnotationCoverage
    data class Partial(val reason: String) : AnnotationCoverage
    data object Unavailable : AnnotationCoverage
}

private fun ParserRange.contains(line: Int, character: Int): Boolean {
    val afterStart = line > start.line || (line == start.line && character >= start.character)
    val beforeEnd = line < end.line || (line == end.line && character < end.character)
    return afterStart && beforeEnd
}
