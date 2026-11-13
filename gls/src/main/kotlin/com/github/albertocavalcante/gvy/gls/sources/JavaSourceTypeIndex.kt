// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import com.github.javaparser.JavaParser
import com.github.javaparser.ParseStart
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Providers
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.Name
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.TypeParameter
import org.eclipse.lsp4j.Position
import java.io.StringReader
import java.util.Collections
import java.util.IdentityHashMap

/** Syntax-only type links for managed Java views. It never infers Java expression or method types. */
internal class JavaSourceTypeIndex private constructor(private val byLine: Map<Int, List<SourceTypeReference>>) :
    SourceTypeIndex {
    override fun at(position: Position): SourceTypeReference? {
        if (position.line < 0 || position.character < 0) return null
        return byLine[position.line].orEmpty().filter {
            position.character >= it.range.startCharacter && position.character < it.range.endCharacter
        }.minByOrNull { it.range.endCharacter - it.range.startCharacter }
    }

    private class Builder(
        private val content: String,
        private val unit: CompilationUnit,
        private val checkCancelled: () -> Unit,
    ) {
        private val budget = SourceTypeIndexBudget()
        private val declarationNames = IdentityHashMap<TypeDeclaration<*>, String>()
        private val lines = Utf16LineIndex(content)
        private val packageName = unit.packageDeclaration.map { budget.name(it.nameAsString) }.orElse("")
        private val typeVariables = unit.findAll(TypeParameter::class.java).mapTo(hashSetOf()) { parameter ->
            budget.name(parameter.nameAsString)
        }
        private val declarations = unit.findAll(TypeDeclaration::class.java)
        private val localDeclarations = declarations.filter { type ->
            val parent = type.parentNode.orElse(null)
            parent is CompilationUnit || parent is TypeDeclaration<*>
        }.groupBy(::typeName)
        private val unmodelledLocalNames = declarations.filter { type ->
            val parent = type.parentNode.orElse(null)
            parent !is CompilationUnit && parent !is TypeDeclaration<*>
        }.mapTo(hashSetOf()) { it.nameAsString }

        private val explicitImports = unit.imports.filter { !it.isAsterisk }.groupBy { it.name.identifier }
            .mapValues { (_, imports) -> imports.map { budget.name(it.nameAsString) }.distinct() }
        private val onDemandImports = unit.imports
            .filter { it.isAsterisk }
            .map { import -> budget.name(import.nameAsString) }
            .distinct()

        private val candidateTiers = hashMapOf<String, List<List<String>>>()
        private val references = linkedMapOf<SourceSpan, SourceTypeReference>()

        fun build(): JavaSourceTypeIndex {
            checkCancelled()
            unit.findAll(ClassOrInterfaceType::class.java).forEach { type ->
                checkCancelled()
                val parent = type.parentNode.orElse(null)
                // Qualifier scopes can be package segments, so only the complete type selects a declaration.
                if (parent !is ClassOrInterfaceType || parent.scope.orElse(null) !== type) {
                    add(type.nameWithScope, type, type.name)
                }
            }
            unit.findAll(AnnotationExpr::class.java).forEach { annotation ->
                checkCancelled()
                add(annotation.nameAsString, annotation, annotation.name)
            }
            unit.imports.filter { !it.isAsterisk && !it.isStatic }.forEach { import ->
                checkCancelled()
                span(import.name, import.nameAsString)?.let { range ->
                    put(SourceTypeReference(range, budget.candidates(listOf(listOf(import.nameAsString)))))
                }
            }
            val grouped = references.values.groupBy { it.range.startLine }
                .mapValues { (_, values) -> Collections.unmodifiableList(values.toList()) }
            checkCancelled()
            return JavaSourceTypeIndex(Collections.unmodifiableMap(grouped))
        }

        private fun add(spelling: String, occurrence: Node, selectedName: Node) {
            checkCancelled()
            val selectedSpelling = when (selectedName) {
                is SimpleName -> selectedName.identifier
                is Name -> selectedName.asString()
                else -> return
            }
            val range = span(selectedName, selectedSpelling) ?: return
            budget.name(spelling)
            val first = spelling.substringBefore('.')
            if (first in typeVariables || first in unmodelledLocalNames) return
            if (hasUnmodelledHierarchy(occurrence)) return
            val local = localTarget(spelling, occurrence)
            if (local != null) {
                put(SourceTypeReference(range, emptyList(), local))
                return
            }
            val explicit = explicitImports[first].orEmpty()
            if (explicit.isNotEmpty()) {
                val candidates = candidateTiers.getOrPut(spelling) {
                    // Duplicate exposed imports are not a license to pick one.
                    if (explicit.size == 1) {
                        budget.candidates(listOf(listOf(explicit.single() + spelling.removePrefix(first))))
                    } else {
                        emptyList()
                    }
                }
                put(SourceTypeReference(range, candidates))
                return
            }
            val candidates = candidateTiers.getOrPut(spelling) {
                val samePackage = if (packageName.isEmpty()) spelling else packageName + "." + spelling
                budget.candidates(
                    buildList {
                        if (spelling.contains('.')) {
                            add(listOf(spelling, samePackage).distinct())
                        } else {
                            add(listOf(samePackage))
                        }
                        if (onDemandImports.size < MAX_CANDIDATES) {
                            add((onDemandImports.map { it + "." + spelling } + ("java.lang." + spelling)).distinct())
                        }
                    },
                )
            }
            put(SourceTypeReference(range, candidates))
        }

        private fun localTarget(spelling: String, occurrence: Node): SourceDefinition? {
            val currentNames = enclosingTypes(occurrence).map(::typeName)
            val wanted = currentNames.map { it + "." + spelling } + qualify(spelling)
            for (candidate in wanted) {
                val matches = localDeclarations[candidate].orEmpty()
                if (matches.size == 1) {
                    val declaration = matches.single()
                    val nameRange = span(declaration.name, declaration.nameAsString) ?: return null
                    val declarationRange = declaration.range.orElse(null)?.let(::span) ?: return null
                    return SourceDefinition(declarationRange, nameRange)
                }
            }
            return null
        }

        private fun enclosingTypes(node: Node): List<TypeDeclaration<*>> =
            generateSequence(node.parentNode.orElse(null)) { it.parentNode.orElse(null) }
                .filterIsInstance<TypeDeclaration<*>>().toList()

        private fun typeName(type: TypeDeclaration<*>): String = declarationNames.getOrPut(type) {
            val nestedName = (enclosingTypes(type).asReversed().map { it.nameAsString } + type.nameAsString)
                .joinToString(".")
            budget.name(qualify(nestedName))
        }

        private fun qualify(name: String): String = if (packageName.isEmpty()) name else packageName + "." + name

        private fun hasUnmodelledHierarchy(node: Node): Boolean = enclosingTypes(node).any {
            it is ClassOrInterfaceDeclaration && (it.extendedTypes.isNotEmpty() || it.implementedTypes.isNotEmpty())
        }

        private fun put(reference: SourceTypeReference) {
            if (references.size < MAX_REFERENCES && reference.range !in references) {
                // References freeze their own list containers, so repeated spellings still consume retained metadata.
                budget.candidates(reference.candidates)
                references[reference.range] = reference
            }
        }

        private fun span(node: Node, spelling: String): SourceSpan? {
            val range = node.range.orElse(null)?.let(::span) ?: return null
            if (range.startLine != range.endLine) return null
            val start = lines.offsetOf(range.startLine, range.startCharacter)
            val end = lines.offsetOf(range.endLine, range.endCharacter)
            return range.takeIf { start <= end && content.substring(start, end) == spelling }
        }

        private fun span(range: com.github.javaparser.Range): SourceSpan? {
            val startLine = range.begin.line - 1
            val startCharacter = range.begin.column - 1
            val endLine = range.end.line - 1
            val endCharacter = range.end.column
            if (!lines.isValidPosition(startLine, startCharacter) || !lines.isValidPosition(endLine, endCharacter)) {
                return null
            }
            return SourceSpan(startLine, startCharacter, endLine, endCharacter)
        }
    }

    companion object {
        private const val MAX_REFERENCES = 16_384
        private const val MAX_CANDIDATES = 32
        private const val MAX_SOURCE_CHARACTERS = 1024 * 1024

        fun build(content: String, checkCancelled: () -> Unit = {}): JavaSourceTypeIndex {
            checkCancelled()
            if (content.length > MAX_SOURCE_CHARACTERS) return JavaSourceTypeIndex(emptyMap())
            val configuration = ParserConfiguration().apply {
                setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                setTabSize(1)
            }
            val parser = JavaParser(configuration)
            val reader = CancellableSourceReader(StringReader(content), checkCancelled)
            val parsed = reader.use { input -> parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(input)) }
            checkCancelled()
            val unit = parsed.result.orElse(null)
            if (!parsed.isSuccessful || unit == null) return JavaSourceTypeIndex(emptyMap())
            return try {
                Builder(content, unit, checkCancelled).build()
            } catch (_: SourceTypeIndexLimitException) {
                JavaSourceTypeIndex(emptyMap())
            }
        }
    }
}
