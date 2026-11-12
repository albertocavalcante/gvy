// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.groovyparser.ast.superClassChain
import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Source-level Spock specification identity for one parsed module, derived only from AST evidence.
 *
 * Classification uses the class hierarchy and the module's own imports and declarations, never
 * source text, so comments, strings and block-like labels cannot establish identity. The usual
 * parser output is pre-resolution, so a SPECIFICATION result means the source binds the superclass
 * to `spock.lang.Specification`. It does not prove that Spock is on the document's classpath or
 * which version it is; that belongs to target/capability evidence. A resolved hierarchy is only
 * as reliable as the compile context that produced it.
 *
 * UNCERTAIN means the module alone cannot decide, for example an external base class or a name
 * supplied only by a star import. Consumers must not report errors or edit source on UNCERTAIN.
 */
sealed interface SpockSourceContext {
    /** No AST is available (for example, unrecoverable syntax errors); identity is unknown, not absent. */
    data object Unavailable : SpockSourceContext

    /**
     * Per-class classification in module order. [isPartial] is true when the AST came from a parse
     * that reported errors, so classes may be missing from [classes].
     */
    class Available internal constructor(val classes: List<SpockClassContext>, val isPartial: Boolean) :
        SpockSourceContext {
        /**
         * The class owning the 0-based LSP position: a declared class wins over the file's script
         * class, then the most deeply nested, then the smallest range. The end position is
         * exclusive, so the column just after a closing brace is outside the class. Linear in the
         * number of classes.
         */
        fun ownerAt(line: Int, column: Int): SpockClassContext? = classes
            .filter { contains(it.classNode, line + 1, column + 1) }
            .minWithOrNull(
                compareBy<SpockClassContext> { it.classNode.isScriptSafe() }
                    .thenByDescending { depth(it.classNode) }
                    .thenBy { span(it.classNode) },
            )

        /**
         * The nearest declared (named, non-anonymous) class owning the position. Use this to ask
         * "is this position inside a specification?": an anonymous class body inside a feature
         * belongs to its enclosing spec for that question, unlike [ownerAt].
         */
        fun declaredOwnerAt(line: Int, column: Int): SpockClassContext? {
            val owner = ownerAt(line, column) ?: return null
            val declared = generateSequence(owner.classNode) { it.outerClass }
                .firstOrNull { !(it is InnerClassNode && it.isAnonymous) } ?: return null
            return classes.firstOrNull { it.classNode === declared }
        }

        private fun contains(node: ClassNode, line: Int, column: Int): Boolean {
            if (node.lineNumber < 1 || node.lastLineNumber < 1) return false
            val afterStart = line > node.lineNumber || (line == node.lineNumber && column >= node.columnNumber)
            val beforeEnd =
                line < node.lastLineNumber || (line == node.lastLineNumber && column < node.lastColumnNumber)
            return afterStart && beforeEnd
        }

        private fun span(node: ClassNode): Long = (node.lastLineNumber - node.lineNumber).toLong() * Int.MAX_VALUE +
            (node.lastColumnNumber - node.columnNumber)

        private fun depth(node: ClassNode): Int = generateSequence(node.outerClass) { it.outerClass }.count()
    }

    companion object {
        fun from(result: ParseResult): SpockSourceContext {
            val module = result.ast ?: return Unavailable
            return from(module, isPartial = !result.isSuccessful)
        }

        fun from(module: ModuleNode, isPartial: Boolean): Available {
            val classifier = SpecificationClassifier(module)
            return Available(module.classes.map(classifier::classify), isPartial)
        }
    }
}

data class SpockClassContext(
    val classNode: ClassNode,
    val status: SpecificationStatus,
    val evidence: SpecificationEvidence,
) {
    /**
     * Whether a provider should treat this class as a specification (#974). A confident verdict decides;
     * UNCERTAIN keeps the previous class-level [SpockDetector.isSpockSpec], so behaviour changes only where
     * the source alone proves the answer. [module] must be the module this context was built from;
     * [specClassNode], when a caller has loaded `spock.lang.Specification`, is passed to that fallback.
     *
     * Retained fallback (#1211 boundaries):
     * - Purpose: keep read-only feedback (completion data variables, definition, test discovery) for
     *   classes the source cannot classify.
     * - Missing evidence: the superclass binding, typically a star-import-only name or a base outside the
     *   compile; target/classpath evidence (#1285 step 2, #1211 row 5) would decide it.
     * - Known failure modes: the detector matches the simple name `Specification` once `spock.lang` is
     *   imported, so an unrelated class named `Specification` can be accepted.
     * - Permitted outputs: read-only feedback only. Never a diagnostic or a source edit; edits require
     *   [SpecificationStatus.SPECIFICATION] (see the table action).
     * - Tests: the "uncertain ... keeps the previous detector" cases in each consumer's tests.
     * - Replacement: decide UNCERTAIN classes from target evidence, then remove this branch.
     *
     * Each fallback decision is traced (logger `SpockClassContext`, level TRACE) with class, evidence and result.
     */
    fun isSpecification(module: ModuleNode, specClassNode: ClassNode? = null): Boolean = when (status) {
        SpecificationStatus.SPECIFICATION -> true
        SpecificationStatus.NOT_SPECIFICATION -> false
        SpecificationStatus.UNCERTAIN -> SpockDetector.isSpockSpec(classNode, module, specClassNode).also { result ->
            fallbackLogger.trace { "Spock fallback: ${classNode.name} is UNCERTAIN ($evidence); detector says $result" }
        }
    }

    private companion object {
        val fallbackLogger = KotlinLogging.logger("SpockClassContext")
    }
}

enum class SpecificationStatus { SPECIFICATION, UNCERTAIN, NOT_SPECIFICATION }

/** Why a class received its [SpecificationStatus]; inspectable for tracing, never shown as a warning. */
enum class SpecificationEvidence {
    /** A resolved hierarchy does (SPECIFICATION) or does not (NOT_SPECIFICATION) contain the Spock base. */
    RESOLVED_HIERARCHY,

    /**
     * The superclass is a source class from another file of the same compilation unit, and every link up
     * to `spock.lang.Specification` (resolved by full name) or Object is such a class or JVM-resolved.
     */
    COMPILED_SOURCE_HIERARCHY,

    /** The superclass is spelled `spock.lang.Specification`. */
    QUALIFIED_SUPERCLASS,

    /** A single-type import, possibly aliased, binds the superclass name to the Spock base. */
    EXPLICIT_IMPORT,

    /** The superclass is declared in this module; its own classification is inherited. */
    MODULE_BASE,

    /** UNCERTAIN: only `import spock.lang.*` supplies the name; same-package classes take precedence. */
    STAR_IMPORT_ONLY,

    /** UNCERTAIN: the superclass is declared outside this module; its hierarchy needs classpath evidence. */
    UNRESOLVED_SUPERCLASS,

    /** UNCERTAIN: more than one module class or import binds the superclass spelling. */
    SHADOWED_NAME,

    /** UNCERTAIN: an enclosing class's superclass may supply a nested class with the bare name. */
    INHERITED_NESTED_SCOPE,

    /** UNCERTAIN: classes in this module extend each other cyclically. */
    MODULE_HIERARCHY_CYCLE,

    /** NOT_SPECIFICATION: the class declares no superclass other than Object. */
    NO_SUPERCLASS,

    /** NOT_SPECIFICATION: the generated script class of a script file. */
    SCRIPT_CLASS,
}

private const val SPECIFICATION_FQN = "spock.lang.Specification"
private val SPECIFICATION_TARGET = setOf(SPECIFICATION_FQN)

/** Applies Groovy name-binding precedence conservatively: anything the module cannot prove stays uncertain. */
private class SpecificationClassifier(private val module: ModuleNode) {
    private val binder = SpockNameBinder(module)

    // Identity, not ClassNode equality: ClassNode compares by name, and duplicate names are common mid-edit.
    private val results = IdentityHashMap<ClassNode, SpockClassContext>()
    private val inProgress: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())

    fun classify(node: ClassNode): SpockClassContext {
        results[node]?.let { return it }
        if (!inProgress.add(node)) {
            return context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.MODULE_HIERARCHY_CYCLE)
        }
        val result = try {
            decide(node)
        } finally {
            inProgress.remove(node)
        }
        results[node] = result
        return result
    }

    private fun decide(node: ClassNode): SpockClassContext {
        val superClass = node.superClass
        val bound = superClass?.let(::compilerBound)
        return when {
            node.isScriptSafe() -> context(
                node,
                SpecificationStatus.NOT_SPECIFICATION,
                SpecificationEvidence.SCRIPT_CLASS,
            )
            superClass == null || superClass.name == ClassHelper.OBJECT_TYPE.name ->
                context(node, SpecificationStatus.NOT_SPECIFICATION, SpecificationEvidence.NO_SUPERCLASS)
            superClass.isResolved -> resolved(node, superClass)
            bound != null && enclosingScopesKnown(node) -> inherit(node, classify(bound))
            else -> compiledSourceHierarchy(node, superClass) ?: bySourceName(node, superClass.name)
        }
    }

    /**
     * Trusts the compiler's binding across files, but only for primary source classes compiled in this
     * module's own compilation unit. Generated missing-class stubs (empty stand-ins compiled into the same
     * unit), placeholders and decompiled or cached classes do not qualify.
     * Each link must be such a class or JVM-resolved; the walk ends at `spock.lang.Specification` resolved
     * by full name or at Object. Any other link, a cycle, or an unknown enclosing scope returns null so the
     * source-name rules decide (UNCERTAIN for a base outside the module). The verdict is only as current
     * as the compilation unit that produced the AST.
     *
     * Limitation: the enclosing-scope check ([enclosingScopesKnown]) runs only for [node]. A nested class
     * in another file along the chain is trusted as the compiler bound it, even if one of its own enclosing
     * classes has a supertype the compile could not see.
     */
    private fun compiledSourceHierarchy(node: ClassNode, superClass: ClassNode): SpockClassContext? {
        if (!inCompilationUnit(superClass) || !enclosingScopesKnown(node)) return null
        return compiledChainStatus(superClass.redirect())
            ?.let { context(node, it, SpecificationEvidence.COMPILED_SOURCE_HIERARCHY) }
    }

    /**
     * Walks superclasses with an identity visited set and plain node properties only. Groovy's own
     * hierarchy helpers (`isScript`, `isDerivedFrom`) loop forever on a cycle (#1369). Null when a link
     * is neither in this compilation unit nor JVM-resolved, the chain cycles, or it ends elsewhere.
     */
    private fun compiledChainStatus(start: ClassNode): SpecificationStatus? {
        val seen: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())
        var link: ClassNode? = start
        var status: SpecificationStatus? = null
        while (link != null && status == null && seen.add(link)) {
            when {
                link.name == ClassHelper.OBJECT_TYPE.name -> status = SpecificationStatus.NOT_SPECIFICATION
                link.name == SPECIFICATION_FQN && link.isResolved -> status = SpecificationStatus.SPECIFICATION
                link.isResolved || inCompilationUnit(link) -> link = link.superClass?.redirect()
                else -> link = null
            }
        }
        return status
    }

    private fun inCompilationUnit(type: ClassNode): Boolean {
        val target = type.redirect()
        val owner = target.module ?: return false
        val unit = module.unit ?: return false
        return target.isPrimaryClassNode && !isStubSourceName(owner.context?.name) &&
            owner.unit === unit && unit.modules.any { it === owner }
    }

    /**
     * True when the compiler could see every nested class that an enclosing class's supertypes might
     * contribute: each enclosing class's whole supertype hierarchy (superclasses and interfaces) is
     * Object, resolved, or in-file with a known hierarchy itself. An unresolvable (stubbed) supertype
     * anywhere up the chain hides its nested classes, so the compiler's choice of an in-file class
     * is not trusted.
     */
    private fun enclosingScopesKnown(node: ClassNode): Boolean = generateSequence(node.outerClass) { it.outerClass }
        .all { outer -> hierarchyKnown(outer, Collections.newSetFromMap(IdentityHashMap())) }

    /** [path] holds the classes on the current walk only, so a cycle is unknown but a diamond is not. */
    private fun hierarchyKnown(type: ClassNode, path: MutableSet<ClassNode>): Boolean {
        if (!path.add(type)) return false
        val supertypes = listOfNotNull(type.superClass) + type.interfaces.orEmpty()
        val known = supertypes.all { supertype ->
            supertype.name == ClassHelper.OBJECT_TYPE.name || supertype.isResolved ||
                compilerBound(supertype)?.let { hierarchyKnown(it, path) } == true
        }
        path.remove(type)
        return known
    }

    private fun resolved(node: ClassNode, superClass: ClassNode): SpockClassContext {
        val isSpecification = (sequenceOf(superClass) + superClass.superClassChain()).any {
            it.name == SPECIFICATION_FQN
        }
        val status = if (isSpecification) SpecificationStatus.SPECIFICATION else SpecificationStatus.NOT_SPECIFICATION
        return context(node, status, SpecificationEvidence.RESOLVED_HIERARCHY)
    }

    /** Maps the shared conservative [SpockNameBinder] result for the superclass spelling. */
    private fun bySourceName(node: ClassNode, rawName: String): SpockClassContext {
        val enclosing = generateSequence(node.outerClass) { it.outerClass }.toList()
        return when (val binding = binder.bind(rawName, enclosing, SPECIFICATION_TARGET)) {
            is NameBinding.Ambiguous -> when (binding.reason) {
                NameBinding.Ambiguity.MULTIPLE_BINDINGS ->
                    context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.SHADOWED_NAME)
                NameBinding.Ambiguity.INHERITED_NESTED_SCOPE ->
                    context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.INHERITED_NESTED_SCOPE)
            }
            is NameBinding.ModuleClass -> inherit(node, classify(binding.node))
            is NameBinding.Qualified -> if (binding.name == SPECIFICATION_FQN) {
                context(node, SpecificationStatus.SPECIFICATION, SpecificationEvidence.QUALIFIED_SUPERCLASS)
            } else {
                uncertain(node)
            }
            is NameBinding.Imported -> if (binding.fqn == SPECIFICATION_FQN) {
                context(node, SpecificationStatus.SPECIFICATION, SpecificationEvidence.EXPLICIT_IMPORT)
            } else {
                uncertain(node)
            }
            is NameBinding.StarImportOnly ->
                context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.STAR_IMPORT_ONLY)
            NameBinding.Unbound -> uncertain(node)
        }
    }

    private fun inherit(node: ClassNode, base: SpockClassContext): SpockClassContext {
        val evidence = if (base.evidence == SpecificationEvidence.MODULE_HIERARCHY_CYCLE) {
            SpecificationEvidence.MODULE_HIERARCHY_CYCLE
        } else {
            SpecificationEvidence.MODULE_BASE
        }
        return context(node, base.status, evidence)
    }

    /**
     * The module class this superclass reference is identical to. A source spelling yields a fresh
     * placeholder node, so identity means the compiler (after resolution, via redirect) or the parser
     * itself (for example an enum constant body extending its enum) already bound it.
     */
    private fun compilerBound(superClass: ClassNode): ClassNode? {
        val target = superClass.redirect()
        return module.classes.firstOrNull { it === target }
    }

    private fun uncertain(node: ClassNode) =
        context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.UNRESOLVED_SUPERCLASS)

    private fun context(node: ClassNode, status: SpecificationStatus, evidence: SpecificationEvidence) =
        SpockClassContext(node, status, evidence)
}
