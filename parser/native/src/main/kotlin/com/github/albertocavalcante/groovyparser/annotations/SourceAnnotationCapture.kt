// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.annotations

import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.nativeapi.AnnotationCoverage
import com.github.albertocavalcante.nativeapi.AnnotationImportBinding
import com.github.albertocavalcante.nativeapi.AnnotationReferenceKind
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserRange
import com.github.albertocavalcante.nativeapi.SourceAnnotationDeclaration
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import com.github.albertocavalcante.nativeapi.SourceAnnotationMetadata
import com.github.albertocavalcante.nativeapi.SourceAnnotationReference
import com.github.albertocavalcante.nativeapi.SourceImportRanges
import kotlinx.collections.immutable.toPersistentList
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.control.io.StringReaderSource
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/** Installed before the analysis policy strips annotations; never evaluates their member expressions. */
internal class SourceAnnotationCapture(request: ParseRequest) : CompilationCustomizer(CompilePhase.CONVERSION) {
    private val sourceName = request.sourceUnitName

    private class RequestState(
        var content: String?,
        var ranges: CompilerSourceRanges?,
        val sourceUri: URI,
        var openSources: Map<URI, String>,
    )

    private var requestState: RequestState? = RequestState(
        request.content,
        null,
        request.uri,
        if (request.parseMode == ParseMode.MINIMAL) emptyMap() else request.openSources,
    )
    private val content: String
        get() = checkNotNull(requestState?.content) { "Source annotation capture was discarded" }
    private val ranges: CompilerSourceRanges
        get() {
            val state = checkNotNull(requestState) { "Source annotation capture was discarded" }
            return state.ranges ?: CompilerSourceRanges(checkNotNull(state.content)).also { state.ranges = it }
        }
    private val sourceUri: URI
        get() = checkNotNull(requestState?.sourceUri) { "Source annotation capture was discarded" }
    private val openSources: Map<URI, String>
        get() = checkNotNull(requestState?.openSources) { "Source annotation capture was discarded" }
    private var captured = false
    private var discarded = false
    private var incompleteReason: String? = null
    private val references = linkedMapOf<Occurrence, PendingReference>()
    private val imports = mutableMapOf<String, ImportCandidates>()
    private val importRanges = mutableMapOf<Pair<Int, Int>, ParserRange>()
    private val sourceAnnotatedImports = mutableSetOf<ParserRange>()
    private val importSelections = mutableMapOf<Pair<Int, Int>, SourceImportRanges>()
    private val declarations = mutableListOf<SourceAnnotationDeclaration>()
    private var retainedEntries = 0
    private var retainedCharacters = 0L
    private var retainedSegments = 0
    private var acceptingMetadata = true

    override fun call(source: SourceUnit, context: GeneratorContext, classNode: ClassNode) {
        if (discarded || source.name != sourceName) {
            return
        }
        if (captured) return
        captured = true
        val module = source.ast
        module.imports.forEach { import -> import.alias?.let { addImport(it, import, isStatic = false) } }
        module.staticImports.forEach { (alias, import) -> addImport(alias, import, isStatic = true) }
        val visitor = CaptureVisitor(source)
        module.classes.forEach(visitor::visitClass)
        module.statementBlock?.visit(visitor)
        module.methods.forEach(visitor::visitMethod)
    }

    /** Freeze only values. The temporary builder and its original compiler nodes are not published. */
    fun finish(module: ModuleNode?, compilationFailed: Boolean): SourceAnnotationIndex {
        try {
            if (discarded || !captured || module == null) return SourceAnnotationIndex.UNAVAILABLE
            // Only compiler-selected source targets survive this request-local builder. Unrelated open buffers
            // do not acquire a declaration walk or a published URI table entry for every held parse.
            val redirectedTypes = references.values.mapNotNull { it.type?.redirect() } +
                imports.values.flatMap { it.all() }.mapNotNull { it.node.type?.redirect() }
            val selectedTypes = redirectedTypes
                .filter { type ->
                    type.isPrimaryClassNode &&
                        type.module?.context?.name != null &&
                        !isStubSourceName(type.module?.context?.name)
                }
                .distinctBy { it.module?.context?.name to it.name }
            val selectedNames = selectedTypes.mapNotNullTo(hashSetOf()) { it.module?.context?.name }
            val selectedSources = selectedRequestSources(selectedNames)
            val frozen = references.values.mapNotNull { pending ->
                val reference = pending.freeze(selectedSources.uris)
                val boundNames = listOfNotNull(reference.binaryName, reference.sourceUnitName)
                if (boundNames.any { !nameFits(it) }) return@mapNotNull null
                if (!retain("", extraCharacters = boundNames.sumOf { it.length }, entries = 0)) return@mapNotNull null
                reference
            }
            val foreign = selectedTypes.filter { it.module?.context?.name != sourceName }
                .groupBy { checkNotNull(it.module.context).name }
                .mapValues { (name, types) -> captureForeignDeclarations(name, types, selectedSources.texts) }
            val coverage = when {
                compilationFailed -> AnnotationCoverage.Partial("Compilation did not complete")
                requestState?.ranges?.let { !it.available || it.exhausted } == true ->
                    AnnotationCoverage.Partial("Source annotation range index exceeds its bounded capacity")
                incompleteReason != null -> AnnotationCoverage.Partial(checkNotNull(incompleteReason))
                else -> AnnotationCoverage.Complete
            }
            val index = SourceAnnotationIndex(
                frozen,
                coverage,
                SourceAnnotationMetadata(
                    importRanges = importRanges,
                    declarations = declarations,
                    sourceUris = selectedSources.uris,
                    importSelections = importSelections,
                    foreignDeclarations = foreign,
                    sourceAnnotatedImports = sourceAnnotatedImports,
                ),
            )
            module.putNodeMetaData(SourceAnnotationIndex::class.java, index)
            return index
        } finally {
            discard()
        }
    }

    private data class SelectedRequestSources(val uris: Map<String, URI?>, val texts: Map<String, String?>)

    private fun selectedRequestSources(selectedNames: Set<String>): SelectedRequestSources {
        if (selectedNames.all { it == sourceName }) {
            return SelectedRequestSources(mapOf(sourceName to sourceUri), emptyMap())
        }
        val requiredNames = selectedNames + sourceName
        val uriCandidates = linkedMapOf<String, URI?>(sourceName to sourceUri)
        val foreignTexts = mutableMapOf<String, String?>()
        for ((uri, text) in openSources) {
            val name = uri.path ?: uri.toString()
            if (name !in requiredNames) continue
            if (name !in uriCandidates) {
                uriCandidates[name] = uri
            } else if (uriCandidates[name] != uri) {
                uriCandidates[name] = null
            }
            if (name in selectedNames && name != sourceName) {
                if (name !in foreignTexts) {
                    foreignTexts[name] = text
                } else if (foreignTexts[name] != text) {
                    foreignTexts[name] = null
                }
            }
        }
        return SelectedRequestSources(
            uris = uriCandidates,
            texts = foreignTexts,
        )
    }

    /** Releases request text, AST nodes, and temporary maps after the frozen index is published or abandoned. */
    fun discard() {
        if (discarded) return
        discarded = true
        requestState?.let { state ->
            state.content = null
            state.ranges = null
            state.openSources = emptyMap()
        }
        requestState = null
        references.clear()
        imports.clear()
        importRanges.clear()
        sourceAnnotatedImports.clear()
        importSelections.clear()
        declarations.clear()
        incompleteReason = null
        retainedEntries = 0
        retainedCharacters = 0L
        retainedSegments = 0
        acceptingMetadata = false
    }

    private fun addImport(alias: String, import: ImportNode, isStatic: Boolean) {
        if (!acceptingMetadata) return
        if (
            !nameFits(alias) ||
            !nameFits(import.className.orEmpty()) ||
            !retain(alias)
        ) {
            return
        }
        val range = ranges.range(import)
        if (range == null) {
            incompleteReason = "An import has no verified source range"
            return
        }
        importRanges[import.lineNumber to import.columnNumber] = range
        if (import.annotations.isNotEmpty()) sourceAnnotatedImports.add(range)
        val selection = if (ranges.offset(range.end) - ranges.offset(range.start) <= MAX_NAME_CHARACTERS) {
            sourceImportSelectionRange(import, content, ranges, range)
        } else {
            limited()
            null
        }
        selection?.let {
            importSelections[import.lineNumber to import.columnNumber] = it
        }
        val candidates = imports.getOrPut(alias, ::ImportCandidates)
        val candidate = ImportCandidate(import, range, "")
        if (isStatic) {
            candidates.staticMember = candidate
        } else {
            candidates.explicit = candidate
        }
    }

    private fun candidates(spelling: String): List<ImportCandidate> = buildList {
        // Groovy import aliases are single identifiers; only the first qualified component can be an alias.
        val separator = spelling.indexOf('.')
        val prefix = if (separator < 0) spelling else spelling.substring(0, separator)
        imports[prefix]?.all().orEmpty().forEach { candidate ->
            val suffix = if (separator < 0) "" else spelling.substring(separator + 1).replace('.', '$')
            add(candidate.copy(suffix = suffix))
        }
    }

    private fun head(annotation: AnnotationNode, enclosingBinaryName: String?) {
        if (!acceptingMetadata) return
        val type = annotation.classNode
        if (!nameFits(type.name) || retainedEntries >= MAX_ENTRIES) {
            limited()
            return
        }
        val verifiedName = verifiedSourceName(
            range = ranges.range(type),
            expected = type.name,
            reportMissingRange = annotation.lineNumber > 0,
            rangeFailure = "An annotation name has no verified source range",
            segmentsFailure = "An annotation name has no verified identifier segments",
        ) ?: return
        val key = Occurrence(verifiedName.range, type.name, AnnotationReferenceKind.HEAD, verifiedName.segments)
        if (key !in references && retain(type.name, verifiedName.segments)) {
            references.putIfAbsent(key, PendingReference(key, type, candidates(type.name), enclosingBinaryName))
        }
    }

    private fun argument(spelling: String, node: org.codehaus.groovy.ast.ASTNode, enclosingBinaryName: String?) {
        if (!acceptingMetadata) return
        val possibleImports = if (nameFits(spelling)) candidates(spelling) else emptyList()
        if (possibleImports.isEmpty()) return
        val verifiedName = verifiedSourceName(
            range = ranges.range(node),
            expected = spelling,
            reportMissingRange = true,
            rangeFailure = "An annotation argument has no verified source range",
            segmentsFailure = "An annotation argument has no verified identifier segments",
        ) ?: return
        val key = Occurrence(verifiedName.range, spelling, AnnotationReferenceKind.ARGUMENT, verifiedName.segments)
        if (key !in references && retain(spelling, verifiedName.segments)) {
            // Member expressions can be replaced/inlined. Preserve candidate imports without inventing a semantic bind.
            references.putIfAbsent(key, PendingReference(key, null, possibleImports, enclosingBinaryName))
        }
    }

    private data class VerifiedSourceName(val range: ParserRange, val segments: List<ParserRange>)

    private fun verifiedSourceName(
        range: ParserRange?,
        expected: String,
        reportMissingRange: Boolean,
        rangeFailure: String,
        segmentsFailure: String,
    ): VerifiedSourceName? {
        val withinLimit = range == null ||
            ranges.offset(range.end) - ranges.offset(range.start) <= MAX_NAME_CHARACTERS
        if (range != null && !withinLimit) limited()

        val verifiedRange = range?.takeIf { withinLimit && normalizeName(ranges.text(it)) == expected }
        if (verifiedRange == null && withinLimit && reportMissingRange) {
            // An absent or mismatched compiler range is not proof of a source occurrence.
            incompleteReason = rangeFailure
        }
        val segments = verifiedRange?.let { sourceTypeNameSegments(content, ranges, it) }
        if (verifiedRange != null && segments == null) incompleteReason = segmentsFailure
        return if (verifiedRange != null && segments != null) VerifiedSourceName(verifiedRange, segments) else null
    }

    private fun nameFits(name: String): Boolean {
        val fitsCapacity = name.length <= MAX_NAME_CHARACTERS
        if (!fitsCapacity) limited()
        return fitsCapacity
    }

    private fun retain(
        name: String,
        segments: List<ParserRange> = emptyList(),
        extraCharacters: Int = 0,
        entries: Int = 1,
    ): Boolean {
        val characters = name.length.toLong() + extraCharacters +
            segments.sumOf { segment -> ranges.offset(segment.end) - ranges.offset(segment.start) }
        if (
            retainedEntries + entries > MAX_ENTRIES ||
            retainedCharacters + characters > MAX_CHARACTERS ||
            retainedSegments + segments.size > MAX_SEGMENTS
        ) {
            limited()
            return false
        }
        retainedEntries += entries
        retainedCharacters += characters
        retainedSegments += segments.size
        return true
    }

    private fun limited() {
        acceptingMetadata = false
        incompleteReason = "Source annotation metadata exceeds its bounded capacity"
    }

    private fun captureForeignDeclarations(
        name: String,
        types: List<ClassNode>,
        foreignTexts: Map<String, String?>,
    ): List<SourceAnnotationDeclaration> {
        // These bytes were supplied in the original request. Disk-only targets retain their proven URI,
        // without reading a newer source revision to manufacture a precise declaration selection.
        val text = foreignTexts[name] ?: return emptyList()
        val foreignRanges = CompilerSourceRanges(text)
        if (!foreignRanges.available) {
            incompleteReason = "Source annotation ranges exceed the source size limit"
            return emptyList()
        }
        val declarations = types.mapNotNull { node ->
            if (!acceptingMetadata || !nameFits(node.name)) return@mapNotNull null
            val declaration = foreignRanges.range(node)
            val selection = declaration?.let { sourceTypeDeclarationName(node, text, foreignRanges, it) }
            if (declaration == null || selection == null) {
                incompleteReason = "A foreign annotation declaration has no verified name range"
                return@mapNotNull null
            }
            if (retain(node.name)) SourceAnnotationDeclaration(node.name, declaration, selection) else null
        }
        if (foreignRanges.exhausted) incompleteReason = "Source annotation range index exceeds its bounded capacity"
        return declarations
    }

    private companion object {
        const val MAX_NAME_CHARACTERS = 4096
        const val MAX_ENTRIES = 16_384
        const val MAX_CHARACTERS = 2L * 1024 * 1024
        const val MAX_SEGMENTS = 65_536
    }

    private data class Occurrence(
        val range: ParserRange,
        val spelling: String,
        val kind: AnnotationReferenceKind,
        val segments: List<ParserRange> = listOf(range),
    )

    private data class ImportCandidate(val node: ImportNode, val range: ParserRange, val suffix: String) {
        fun binaryName(): String {
            val owner = node.type.name
            val imported = node.fieldName?.let { "$owner\$$it" } ?: owner
            return if (suffix.isEmpty()) imported else "$imported\$$suffix"
        }
    }

    /** Groovy keeps ordinary and static imports in separate alias namespaces. */
    private data class ImportCandidates(
        var explicit: ImportCandidate? = null,
        var staticMember: ImportCandidate? = null,
    ) {
        fun all(): List<ImportCandidate> = listOfNotNull(explicit, staticMember)
    }

    private data class PendingReference(
        val occurrence: Occurrence,
        val type: ClassNode?,
        val candidates: List<ImportCandidate>,
        val enclosingBinaryName: String?,
    ) {
        fun freeze(sourceUris: Map<String, URI?>): SourceAnnotationReference {
            val selected = type?.redirect()?.takeIf { declaration ->
                val hasCompilerBinding =
                    declaration.isResolved ||
                        (declaration.isPrimaryClassNode && declaration.module?.context?.name != null)
                hasCompilerBinding && !isStubSourceName(declaration.module?.context?.name)
            }
            val binaryName = selected?.name
            val sourceUnitName = selected?.takeIf { it.isPrimaryClassNode }?.module?.context?.name
            val reader = selected?.takeIf { it.isPrimaryClassNode }?.module?.context?.source
            val sourceUri = sourceUnitName?.let { sourceUris[it] }
                ?: reader?.takeUnless { it is StringReaderSource }?.getURI()?.takeIf { it.scheme == "file" }
            val matching = binaryName?.let { name -> candidates.firstOrNull { it.binaryName() == name } }
            val binding = when {
                matching != null -> AnnotationImportBinding.Explicit(matching.range)
                binaryName != null || candidates.isEmpty() -> AnnotationImportBinding.None
                else -> AnnotationImportBinding.Unknown(candidates.map { it.range })
            }
            return SourceAnnotationReference(
                occurrence.spelling,
                occurrence.range,
                occurrence.segments.toPersistentList(),
                occurrence.kind,
                enclosingBinaryName,
                binaryName,
                binding,
                sourceUnitName,
                sourceUri,
            )
        }
    }

    @Suppress("TooManyFunctions") // Groovy's visitor API has a separate callback for each source type-use location.
    private inner class CaptureVisitor(private val source: SourceUnit) : ClassCodeVisitorSupport() {
        private val classes = identitySet<ClassNode>()
        private val types = identitySet<ClassNode>()
        private val annotations = identitySet<AnnotationNode>()
        private val modules = identitySet<ModuleNode>()
        private val packages = identitySet<PackageNode>()
        private var argumentDepth = 0
        private var scope: ClassNode? = null

        override fun getSourceUnit(): SourceUnit = source

        override fun visitAnnotations(node: AnnotatedNode) = node.annotations.forEach(::visitAnnotation)

        override fun visitAnnotation(node: AnnotationNode) {
            if (!annotations.add(node)) return
            head(node, scope?.name)
            argumentDepth++
            try {
                super.visitAnnotation(node)
            } finally {
                argumentDepth--
            }
        }

        override fun visitImports(node: ModuleNode) {
            if (!modules.add(node)) return
            val previous = scope
            scope = null
            try {
                super.visitImports(node)
            } finally {
                scope = previous
            }
        }

        override fun visitPackage(node: PackageNode?) {
            if (node == null || !packages.add(node)) return
            val previous = scope
            scope = null
            try {
                super.visitPackage(node)
            } finally {
                scope = previous
            }
        }

        override fun visitClass(node: ClassNode) {
            if (!classes.add(node)) return
            val previous = scope
            scope = node
            try {
                if (node.isAnnotationDefinition && acceptingMetadata) {
                    val declaration = captureDeclaration(node)
                    if (declaration == null) {
                        incompleteReason = "An annotation declaration has no verified name range"
                    } else {
                        declarations.add(declaration)
                    }
                }
                type(node.unresolvedSuperClass)
                node.interfaces.forEach(::type)
                node.genericsTypes?.forEach { generic ->
                    type(generic.type)
                    generic.upperBounds?.forEach(::type)
                    type(generic.lowerBound)
                }
                super.visitClass(node)
                node.innerClasses.forEachRemaining(::visitClass)
            } finally {
                scope = previous
            }
        }

        private fun captureDeclaration(node: ClassNode): SourceAnnotationDeclaration? = ranges.range(node)
            ?.let { declarationRange ->
                sourceTypeDeclarationName(node, content, ranges, declarationRange)
                    ?.let { nameRange -> declarationRange to nameRange }
            }
            ?.takeIf { nameFits(node.name) && retain(node.name) }
            ?.let { (declarationRange, nameRange) ->
                SourceAnnotationDeclaration(node.name, declarationRange, nameRange)
            }

        override fun visitMethod(node: MethodNode) {
            signature(node)
            val defaultValue = node.hasAnnotationDefault()
            if (defaultValue) argumentDepth++
            try {
                super.visitMethod(node)
            } finally {
                if (defaultValue) argumentDepth--
            }
        }

        override fun visitConstructor(node: ConstructorNode) {
            signature(node)
            super.visitConstructor(node)
        }

        private fun signature(node: MethodNode) {
            type(node.returnType)
            node.parameters.forEach { type(it.type) }
            node.exceptions?.forEach(::type)
            node.genericsTypes?.forEach { generic ->
                type(generic.type)
                generic.upperBounds?.forEach(::type)
                type(generic.lowerBound)
            }
        }

        override fun visitField(node: FieldNode) {
            type(node.type)
            super.visitField(node)
        }

        override fun visitProperty(node: PropertyNode) {
            type(node.type)
            super.visitProperty(node)
        }

        override fun visitDeclarationExpression(expression: DeclarationExpression) {
            type(expression.leftExpression.type)
            super.visitDeclarationExpression(expression)
        }

        override fun visitVariableExpression(expression: VariableExpression) {
            type(expression.type)
            if (argumentDepth > 0) argument(expression.name, expression, scope?.name)
            super.visitVariableExpression(expression)
        }

        override fun visitClassExpression(expression: ClassExpression) {
            type(expression.type)
            if (argumentDepth > 0) argument(expression.type.name, expression, scope?.name)
            super.visitClassExpression(expression)
        }

        override fun visitConstantExpression(expression: ConstantExpression) {
            if (expression is AnnotationConstantExpression) {
                (expression.value as? AnnotationNode)?.let(::visitAnnotation)
            }
            super.visitConstantExpression(expression)
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            createdAnnotations(call.type, call)
            type(call.type)
            super.visitConstructorCallExpression(call)
        }

        override fun visitArrayExpression(expression: ArrayExpression) {
            createdAnnotations(expression.elementType, expression)
            type(expression.elementType)
            type(expression.type)
            super.visitArrayExpression(expression)
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            call.genericsTypes?.forEach { generic ->
                type(generic.type)
                generic.upperBounds?.forEach(::type)
                type(generic.lowerBound)
            }
            super.visitMethodCallExpression(call)
        }

        override fun visitCastExpression(expression: CastExpression) {
            type(expression.type)
            super.visitCastExpression(expression)
        }

        override fun visitClosureExpression(expression: ClosureExpression) {
            expression.parameters?.forEach { parameter ->
                visitAnnotations(parameter)
                type(parameter.type)
            }
            if (argumentDepth > 0) incompleteReason = "An annotation contains a closure argument"
            super.visitClosureExpression(expression)
        }

        override fun visitCatchStatement(statement: CatchStatement) {
            visitAnnotations(statement.variable)
            type(statement.variable.type)
            super.visitCatchStatement(statement)
        }

        override fun visitForLoop(statement: ForStatement) {
            visitAnnotations(statement.variable)
            type(statement.variable.type)
            super.visitForLoop(statement)
        }

        private fun type(node: ClassNode?) {
            if (node == null || !types.add(node)) return
            node.typeAnnotations.forEach(::visitAnnotation)
            if (node.isArray) type(node.componentType)
            node.genericsTypes?.forEach { generic ->
                type(generic.type)
                generic.upperBounds?.forEach(::type)
                type(generic.lowerBound)
            }
        }

        private fun createdAnnotations(type: ClassNode, owner: org.codehaus.groovy.ast.ASTNode) {
            val bounds = ranges.range(owner) ?: return
            val start = ranges.offset(bounds.start)
            val end = ranges.offset(bounds.end)
            type.annotations.forEach { annotation ->
                val range = ranges.range(annotation) ?: return@forEach
                if (ranges.offset(range.start) >= start && ranges.offset(range.end) <= end) {
                    visitAnnotation(annotation)
                }
            }
        }
    }
}

private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

private fun normalizeName(text: String): String = buildString {
    var offset = 0
    while (offset < text.length) {
        when {
            text[offset].isWhitespace() -> offset++
            text.startsWith("/*", offset) -> {
                val end = text.indexOf("*/", offset + 2)
                if (end < 0) return text
                offset = end + 2
            }
            text.startsWith("//", offset) -> {
                while (offset < text.length && text[offset] != '\r' && text[offset] != '\n') offset++
            }
            else -> append(text[offset++])
        }
    }
}
