// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.annotations.CompilerSourceRanges
import com.github.albertocavalcante.groovyparser.annotations.sourceTypeDeclarationName
import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.gvy.gls.utils.skipSourceTrivia
import com.github.albertocavalcante.gvy.gls.utils.sourceIdentifierEnd
import com.github.albertocavalcante.nativeapi.AnnotationCoverage
import com.github.albertocavalcante.nativeapi.AnnotationReferenceKind
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.ResolveVisitor
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/** Detached, syntax-only type links for managed Groovy source. Dynamic member inference is deliberately absent. */
internal class GroovySourceTypeIndex private constructor(private val byLine: Map<Int, List<SourceTypeReference>>) :
    SourceTypeIndex {

    override fun at(position: Position): SourceTypeReference? {
        if (position.line < 0 || position.character < 0) return null
        return byLine[position.line].orEmpty()
            .filter { reference -> reference.range.contains(position) }
            .minWithOrNull(
                compareBy<SourceTypeReference> { it.range.endLine - it.range.startLine }
                    .thenBy { it.range.endCharacter - it.range.startCharacter },
            )
    }

    companion object {
        private const val MAX_CONTENT_CHARACTERS = 1024 * 1024
        private const val MAX_REFERENCES = 16_384
        private const val MAX_CANDIDATES = 128
        private val SOURCE_URI = URI.create("file:///__gvy_source__/Library.groovy")

        fun build(content: String, checkCancelled: () -> Unit = {}): GroovySourceTypeIndex {
            checkCancelled()
            if (content.length > MAX_CONTENT_CHARACTERS) return GroovySourceTypeIndex(emptyMap())
            val parsed = GroovyParserFacade().parse(
                ParseRequest(
                    SOURCE_URI,
                    content,
                    compilePhase = Phases.CONVERSION,
                    parseMode = ParseMode.MINIMAL,
                    checkCancelled = checkCancelled,
                ),
            )
            // The request owns this loader; the index retains no compiler or loader objects.
            return parsed.compilationUnit.classLoader.use {
                try {
                    if (parsed.isSuccessful && parsed.ast != null) {
                        Builder(content, parsed, checkCancelled).build()
                    } else {
                        GroovySourceTypeIndex(emptyMap())
                    }
                } catch (_: SourceTypeIndexLimitException) {
                    GroovySourceTypeIndex(emptyMap())
                }
            }
        }
    }

    // Map each source type position, then bind declarations after the walk.
    @Suppress("TooManyFunctions")
    private class Builder(
        private val content: String,
        private val parsed: ParseResult,
        private val checkCancelled: () -> Unit,
    ) : ClassCodeVisitorSupport() {
        private val budget = SourceTypeIndexBudget()
        private val module = checkNotNull(parsed.ast)
        private val ranges = CompilerSourceRanges(content)
        private val packageName = budget.name(module.packageName.orEmpty().removeSuffix("."))
        private val imports = module.imports
            .groupBy { node -> budget.name(node.alias) }
            .mapValues { (_, nodes) -> nodes.map { node -> budget.name(node.className) }.distinct() }
        private val staticImports = module.staticImports.mapKeys { (alias, _) -> budget.name(alias) }
            .mapValues { (_, node) ->
                val owner = budget.name(node.className)
                val member = budget.name(node.fieldName)
                budget.name("$owner.$member")
            }
        private val stars = (
            module.starImports.mapNotNull { it.packageName?.removeSuffix(".") } +
                module.staticStarImports.values.mapNotNull { it.className }
            )
            .map(budget::name)
            .distinct()
        private val candidateCache = mutableMapOf<String, List<List<String>>>()
        private val exposedCache = mutableMapOf<String, List<List<String>>?>()
        private var methodScope: MethodNode? = null
        private val genericMethods = mutableListOf<MethodNode>()
        private val values = hashSetOf<String>()
        private val classes = identitySet<ClassNode>()
        private val types = identitySet<ClassNode>()
        private val declarations = linkedMapOf<String, Declaration>()
        private val classNodes = mutableMapOf<String, ClassNode>()
        private val sourceNames = IdentityHashMap<ClassNode, String>()
        private val pending = linkedMapOf<SourceSpan, Pending>()
        private val direct = linkedMapOf<SourceSpan, SourceTypeReference>()
        private var scope: ClassNode? = null
        private var hasLocalClasses = false

        override fun getSourceUnit(): SourceUnit = parsed.sourceUnit

        fun build(): GroovySourceTypeIndex {
            (module.imports + module.staticImports.values + module.staticStarImports.values).forEach(::importReference)
            module.classes.forEach(::visitClass)
            module.statementBlock?.visit(this)
            module.methods.forEach(::visitMethod)
            parsed.sourceAnnotations.references.forEach { reference ->
                val kind = if (reference.kind == AnnotationReferenceKind.HEAD) Kind.ANNOTATION else Kind.ARGUMENT
                if (kind == Kind.ARGUMENT && parsed.sourceAnnotations.coverage != AnnotationCoverage.Complete) {
                    return@forEach
                }
                reference.nameSegments.forEach { segment ->
                    val span = segment.toSpan()
                    val enclosing = reference.enclosingBinaryName?.let(classNodes::get)
                    put(
                        Pending(
                            reference.spelling,
                            span,
                            kind,
                            enclosing,
                            isTypeVariable(reference.spelling, enclosing, span, annotation = true),
                        ),
                    )
                }
            }
            val references = direct.values + pending.values.mapNotNull(::bind)
            val grouped = linkedMapOf<Int, MutableList<SourceTypeReference>>()
            references.forEach { reference ->
                for (line in reference.range.startLine..reference.range.endLine) {
                    grouped.getOrPut(line, ::mutableListOf).add(reference)
                }
            }
            val immutable = grouped.mapValues { (_, entries) -> Collections.unmodifiableList(entries.toList()) }
            return GroovySourceTypeIndex(Collections.unmodifiableMap(immutable))
        }

        private fun bind(reference: Pending): SourceTypeReference? {
            checkCancelled()
            val firstName = reference.spelling.substringBefore('.')
            val packageQualified = isPackageQualified(reference)
            val hasHierarchy = hasHierarchy(reference.scope)
            return when {
                reference.typeVariable -> null
                !canBindArgument(reference, firstName, hasHierarchy) -> null
                packageQualified -> bindGlobally(reference)
                hasHierarchy -> bindInCurrentHierarchy(reference)
                hasLocalClasses -> bindToLocal(reference)
                else -> bindToLocal(reference) ?: bindGlobally(reference)
            }
        }

        private fun canBindArgument(reference: Pending, firstName: String, hierarchy: Boolean): Boolean =
            reference.kind != Kind.ARGUMENT ||
                (hasExplicitClassImport(firstName) && isArgumentNamespaceSafe(reference, hierarchy))

        private fun hasExplicitClassImport(firstName: String): Boolean =
            firstName in imports && !hasValueOrStaticShadow(firstName) && module.staticStarImports.isEmpty()

        private fun hasValueOrStaticShadow(firstName: String): Boolean =
            firstName in values || firstName in staticImports

        private fun isArgumentNamespaceSafe(reference: Pending, hierarchy: Boolean): Boolean =
            !hasLocalClasses && ('.' in reference.spelling || !hierarchy)

        private fun bindInCurrentHierarchy(reference: Pending): SourceTypeReference? =
            localInCurrentScope(reference)?.let { definition ->
                retainedReference(reference.range, emptyList(), definition)
            }

        private fun bindToLocal(reference: Pending): SourceTypeReference? =
            local(reference)?.let { definition -> retainedReference(reference.range, emptyList(), definition) }

        private fun bindGlobally(reference: Pending): SourceTypeReference? {
            val explicit = exposed(reference.spelling)
            if (explicit != null) return retainedReference(reference.range, explicit)
            val candidates = candidateCache.getOrPut(reference.spelling) {
                val spelling = reference.spelling
                val samePackage = qualify(spelling)
                val tiers = buildList {
                    val directNames = if (spelling.contains('.')) {
                        listOf(spelling, samePackage).distinct()
                    } else {
                        listOf(samePackage)
                    }
                    add(directNames)
                    if (stars.size > MAX_CANDIDATES) throw SourceTypeIndexLimitException()
                    add(stars.map { "$it.$spelling" })
                    val defaults = ResolveVisitor.DEFAULT_IMPORTS.map { it + spelling }.toMutableList()
                    if (spelling == "BigInteger" || spelling == "BigDecimal") defaults.add("java.math.$spelling")
                    add(defaults.distinct())
                }.filter(List<String>::isNotEmpty)
                budget.candidates(tiers)
            }
            return retainedReference(reference.range, candidates)
        }

        /** An import alias is one identifier, so a qualified use needs at most one prefix lookup. */
        private fun exposed(spelling: String): List<List<String>>? {
            if (exposedCache.containsKey(spelling)) return exposedCache[spelling]
            val separator = spelling.indexOf('.')
            val prefix = if (separator < 0) spelling else spelling.substring(0, separator)
            val imported = imports[prefix] ?: staticImports[prefix]?.let(::listOf)
            val candidates = imported?.let { importedNames ->
                val suffix = if (separator < 0) "" else spelling.substring(separator)
                val matches = importedNames.map { name -> name + suffix }.distinct()
                budget.candidates(if (matches.size == 1) listOf(matches) else emptyList())
            }
            exposedCache[spelling] = candidates
            return candidates
        }

        private fun retainedReference(
            range: SourceSpan,
            candidates: List<List<String>>,
            localDefinition: SourceDefinition? = null,
        ): SourceTypeReference = SourceTypeReference(range, budget.candidates(candidates), localDefinition)

        private fun local(reference: Pending): SourceDefinition? {
            val owners = generateSequence(reference.scope) { it.outerClass }
                .map { owner -> sourceName(owner) }
                .toList()
            val candidates = owners.map { "$it.${reference.spelling}" } +
                listOf(qualify(reference.spelling), reference.spelling)
            return candidates.firstNotNullOfOrNull { declarations[it]?.definition }
        }

        private fun localInCurrentScope(reference: Pending): SourceDefinition? {
            val owner = reference.scope ?: return null
            return declarations["${sourceName(owner)}.${reference.spelling}"]?.definition
        }

        private fun isPackageQualified(reference: Pending): Boolean {
            val firstName = reference.spelling.substringBefore('.')
            // Lowercase-leading dotted names are package paths; uppercase-leading paths may be nested types.
            return '.' in reference.spelling &&
                firstName.firstOrNull()?.isLowerCase() == true &&
                firstName !in imports &&
                firstName !in staticImports
        }

        private fun hasHierarchy(node: ClassNode?): Boolean = generateSequence(node) { it.outerClass }.any { owner ->
            owner.interfaces.any { ranges.range(it) != null } ||
                owner.unresolvedSuperClass?.let { superType ->
                    superType.name != "java.lang.Object" &&
                        superType.name != "Object" &&
                        ranges.range(superType) != null
                } == true
        }

        private fun qualify(name: String): String =
            budget.name(if (packageName.isEmpty()) name else "$packageName.$name")

        private fun sourceName(node: ClassNode): String = sourceNames.getOrPut(node) {
            budget.name(node.name)
            val qualifiedSourceName = node.outerClass?.let { outer ->
                sourceName(outer) + "." + node.name.removePrefix(outer.name + '$')
            } ?: node.name
            budget.name(qualifiedSourceName)
        }

        private fun importReference(node: ImportNode) {
            val range = parsed.sourceAnnotations.importSelectionRange(node) ?: return
            val owner = budget.name(node.className ?: return)
            val name = if (node.isStatic && node.fieldName != null) {
                budget.name("$owner.${node.fieldName}")
            } else {
                owner
            }
            val span = range.toSpan()
            if (span !in direct) {
                checkReferenceCapacity()
                direct[span] = retainedReference(span, listOf(listOf(name)))
            }
            if (!node.isStatic) importAlias(node, range, name)
        }

        private fun importAlias(node: ImportNode, typeRange: ParserRange, binaryName: String) {
            val alias = node.alias ?: return
            val span = aliasSelectionRange(node, typeRange, alias) ?: return
            budget.name(alias)
            checkReferenceCapacity()
            direct[span] = retainedReference(span, listOf(listOf(binaryName)))
        }

        private fun aliasSelectionRange(node: ImportNode, typeRange: ParserRange, alias: String): SourceSpan? {
            val declaration = ranges.range(node) ?: return null
            val limit = ranges.offset(declaration.end)
            val keyword = skipSourceTrivia(content, ranges.offset(typeRange.end), limit)
            val keywordEnd = sourceIdentifierEnd(content, keyword, limit)
                ?.takeIf { end -> content.substring(keyword, end) == "as" } ?: return null
            val start = skipSourceTrivia(content, keywordEnd, limit)
            return sourceIdentifierEnd(content, start, limit)
                ?.takeIf { end -> content.substring(start, end) == alias }
                ?.let { end -> ParserRange(ranges.positionAt(start), ranges.positionAt(end)).toSpan() }
        }

        override fun visitClass(node: ClassNode) {
            checkCancelled()
            if (!classes.add(node)) return
            classNodes[budget.name(node.name)] = node
            if (node.enclosingMethod != null) hasLocalClasses = true
            val previous = scope
            scope = node
            try {
                if (!node.isScriptSafe()) {
                    val declarationRange = ranges.range(node)
                    val nameRange = declarationRange?.let { sourceTypeDeclarationName(node, content, ranges, it) }
                    if (declarationRange != null && nameRange != null) {
                        declarations[sourceName(node)] = Declaration(
                            SourceDefinition(declarationRange.toSpan(), nameRange.toSpan()),
                        )
                    }
                }
                node.genericsTypes?.forEach { budget.name(it.name) }
                generic(node.genericsTypes, node)
                type(node.unresolvedSuperClass, node)
                node.interfaces.forEach { type(it, node) }
                super.visitClass(node)
                node.innerClasses.forEachRemaining(::visitClass)
            } finally {
                scope = previous
            }
        }

        override fun visitMethod(node: MethodNode) {
            withMethodScope(node) {
                signature(node)
                super.visitMethod(node)
            }
        }

        override fun visitConstructor(node: ConstructorNode) {
            withMethodScope(node) {
                signature(node)
                super.visitConstructor(node)
            }
        }

        private inline fun withMethodScope(node: MethodNode, visit: () -> Unit) {
            val previous = methodScope
            methodScope = node
            node.genericsTypes?.forEach { budget.name(it.name) }
            if (!node.genericsTypes.isNullOrEmpty()) genericMethods.add(node)
            try {
                visit()
            } finally {
                methodScope = previous
            }
        }

        private fun signature(node: MethodNode) {
            generic(node.genericsTypes, node)
            type(node.returnType, node)
            node.parameters.forEach(::parameter)
            node.exceptions?.forEach { type(it, node) }
        }

        private fun parameter(node: Parameter) {
            values.add(budget.name(node.name))
            type(node.type, node)
        }

        override fun visitField(node: FieldNode) {
            values.add(budget.name(node.name))
            type(node.type, node)
            super.visitField(node)
        }

        override fun visitProperty(node: PropertyNode) {
            values.add(budget.name(node.name))
            type(node.type, node)
            super.visitProperty(node)
        }

        override fun visitDeclarationExpression(expression: DeclarationExpression) {
            when (val left = expression.leftExpression) {
                is VariableExpression -> values.add(budget.name(left.name))
                is TupleExpression -> left.expressions.filterIsInstance<VariableExpression>().forEach { variable ->
                    values.add(budget.name(variable.name))
                }
            }
            type(expression.leftExpression.type, expression)
            super.visitDeclarationExpression(expression)
        }

        override fun visitClosureExpression(expression: ClosureExpression) {
            expression.parameters?.forEach(::parameter)
            super.visitClosureExpression(expression)
        }

        override fun visitCatchStatement(statement: CatchStatement) {
            parameter(statement.variable)
            super.visitCatchStatement(statement)
        }

        override fun visitForLoop(statement: ForStatement) {
            parameter(statement.variable)
            super.visitForLoop(statement)
        }

        override fun visitClassExpression(expression: ClassExpression) {
            type(expression.type, expression)
            super.visitClassExpression(expression)
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            type(call.type, call)
            super.visitConstructorCallExpression(call)
        }

        override fun visitCastExpression(expression: CastExpression) {
            type(expression.type, expression)
            super.visitCastExpression(expression)
        }

        override fun visitArrayExpression(expression: ArrayExpression) {
            type(expression.elementType, expression)
            super.visitArrayExpression(expression)
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            generic(call.genericsTypes, call)
            super.visitMethodCallExpression(call)
        }

        private fun generic(generics: Array<GenericsType>?, owner: ASTNode) {
            generics?.forEach { generic ->
                type(generic.type, owner)
                generic.upperBounds?.forEach { type(it, owner) }
                type(generic.lowerBound, owner)
            }
        }

        private fun type(node: ClassNode?, owner: ASTNode) {
            checkCancelled()
            if (node == null || !types.add(node)) return
            val range = ranges.range(node)
            val ownerRange = ranges.range(owner)
            if (
                range != null &&
                ownerRange != null &&
                ownerRange.toSpan().contains(range.toSpan())
            ) {
                typeName(range, node.unresolvedName.removeSuffix("[]"))?.let { (spelling, pieces) ->
                    addTypeReferences(spelling, pieces)
                }
            }
            if (node.isArray) type(node.componentType, owner)
            generic(node.genericsTypes, owner)
        }

        private fun addTypeReferences(spelling: String, pieces: List<ParserRange>) {
            if (spelling !in NON_TYPES) {
                pieces.forEach { piece ->
                    val span = piece.toSpan()
                    put(Pending(spelling, span, Kind.TYPE, scope, isTypeVariable(spelling, scope, span)))
                }
            }
        }

        private fun typeName(range: ParserRange, expected: String): Pair<String, List<ParserRange>>? {
            budget.name(expected)
            val startOffset = ranges.offset(range.start)
            val limit = ranges.offset(range.end)
            if (limit - startOffset > SourceTypeIndexBudget.MAX_NAME_CHARACTERS) {
                throw SourceTypeIndexLimitException()
            }
            val parsed = readTypeName(startOffset, limit) ?: return null
            val (spelling, pieces) = parsed
            return (spelling to pieces).takeIf {
                spelling == expected || spelling == expected.substringAfterLast('.')
            }
        }

        private fun readTypeName(startOffset: Int, limit: Int): Pair<String, List<ParserRange>>? {
            var start = skipSourceTrivia(content, startOffset, limit)
            val firstEnd = sourceIdentifierEnd(content, start, limit) ?: return null
            val names = mutableListOf(content.substring(start, firstEnd))
            val pieces = mutableListOf(ParserRange(ranges.positionAt(start), ranges.positionAt(firstEnd)))
            var end = firstEnd
            while (true) {
                val dot = skipSourceTrivia(content, end, limit)
                if (dot >= limit || content[dot] != '.') break
                start = skipSourceTrivia(content, dot + 1, limit)
                end = sourceIdentifierEnd(content, start, limit) ?: return null
                names.add(content.substring(start, end))
                pieces.add(ParserRange(ranges.positionAt(start), ranges.positionAt(end)))
            }
            return names.joinToString(".") to pieces
        }

        private fun isTypeVariable(
            spelling: String,
            owner: ClassNode?,
            span: SourceSpan,
            annotation: Boolean = false,
        ): Boolean {
            val name = spelling.substringBefore('.')
            fun matches(generics: Array<GenericsType>?): Boolean = generics?.any { it.name == name } == true
            if (generateSequence(owner) { it.outerClass }.any { matches(it.genericsTypes) }) return true
            if (methodScope?.let { matches(it.genericsTypes) } == true) return true
            // Annotation references are collected after the visitor has left method scopes.
            return annotation && genericMethods.any { method ->
                matches(method.genericsTypes) && ranges.range(method)?.toSpan()?.contains(span) == true
            }
        }

        private fun put(reference: Pending) {
            if (reference.range in pending) return
            checkReferenceCapacity()
            budget.name(reference.spelling)
            pending[reference.range] = reference
        }

        private fun checkReferenceCapacity() {
            if (pending.size + direct.size >= MAX_REFERENCES) throw SourceTypeIndexLimitException()
        }

        private enum class Kind { TYPE, ANNOTATION, ARGUMENT }
        private data class Pending(
            val spelling: String,
            val range: SourceSpan,
            val kind: Kind,
            val scope: ClassNode?,
            val typeVariable: Boolean,
        )
        private data class Declaration(val definition: SourceDefinition)
    }
}

private fun ParserRange.toSpan(): SourceSpan = SourceSpan(start.line, start.character, end.line, end.character)

private fun SourceSpan.contains(position: Position): Boolean =
    (position.line > startLine || (position.line == startLine && position.character >= startCharacter)) &&
        (position.line < endLine || (position.line == endLine && position.character < endCharacter))

private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

private val NON_TYPES = setOf(
    "def",
    "var",
    "void",
    "boolean",
    "byte",
    "short",
    "int",
    "long",
    "float",
    "double",
    "char",
)
