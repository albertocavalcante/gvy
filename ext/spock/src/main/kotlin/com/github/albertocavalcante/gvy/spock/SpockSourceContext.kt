package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
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
                compareBy<SpockClassContext> { it.classNode.isScript }
                    .thenByDescending { depth(it.classNode) }
                    .thenBy { span(it.classNode) },
            )

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
)

enum class SpecificationStatus { SPECIFICATION, UNCERTAIN, NOT_SPECIFICATION }

/** Why a class received its [SpecificationStatus]; inspectable for tracing, never shown as a warning. */
enum class SpecificationEvidence {
    /** A resolved hierarchy does (SPECIFICATION) or does not (NOT_SPECIFICATION) contain the Spock base. */
    RESOLVED_HIERARCHY,

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

    /** UNCERTAIN: classes in this module extend each other cyclically. */
    MODULE_HIERARCHY_CYCLE,

    /** NOT_SPECIFICATION: the class declares no superclass other than Object. */
    NO_SUPERCLASS,

    /** NOT_SPECIFICATION: the generated script class of a script file. */
    SCRIPT_CLASS,
}

private const val SPECIFICATION_FQN = "spock.lang.Specification"
private const val SPOCK_PACKAGE = "spock.lang."

/** Applies Groovy name-binding precedence conservatively: anything the module cannot prove stays uncertain. */
private class SpecificationClassifier(private val module: ModuleNode) {
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
        return when {
            node.isScript -> context(node, SpecificationStatus.NOT_SPECIFICATION, SpecificationEvidence.SCRIPT_CLASS)
            superClass == null || superClass.name == ClassHelper.OBJECT_TYPE.name ->
                context(node, SpecificationStatus.NOT_SPECIFICATION, SpecificationEvidence.NO_SUPERCLASS)
            superClass.isResolved -> resolved(node, superClass)
            else -> bySourceName(node, superClass.name)
        }
    }

    private fun resolved(node: ClassNode, superClass: ClassNode): SpockClassContext {
        val isSpecification = generateSequence(superClass) { it.superClass }.any { it.name == SPECIFICATION_FQN }
        val status = if (isSpecification) SpecificationStatus.SPECIFICATION else SpecificationStatus.NOT_SPECIFICATION
        return context(node, status, SpecificationEvidence.RESOLVED_HIERARCHY)
    }

    /**
     * Collects every binding the module can see for the superclass spelling: a top-level class, a
     * class nested in an enclosing class, and a single-type import. More than one binding stays
     * uncertain instead of depending on Groovy's precedence between them. Nested classes of an
     * enclosing class's superclass are not considered.
     */
    private fun bySourceName(node: ClassNode, rawName: String): SpockClassContext {
        val name = rawName.replace('$', '.')
        val simple = '.' !in name
        val moduleBases = moduleClasses(name) + if (simple) enclosingNested(node, name) else emptyList()
        val explicit = if (simple) module.getImport(name)?.className else null
        val bindings = moduleBases.distinctBy { System.identityHashCode(it) }.size + if (explicit != null) 1 else 0
        return when {
            bindings > 1 -> context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.SHADOWED_NAME)
            moduleBases.isNotEmpty() -> inherit(node, classify(moduleBases.first()))
            name == SPECIFICATION_FQN ->
                context(node, SpecificationStatus.SPECIFICATION, SpecificationEvidence.QUALIFIED_SUPERCLASS)
            explicit == SPECIFICATION_FQN ->
                context(node, SpecificationStatus.SPECIFICATION, SpecificationEvidence.EXPLICIT_IMPORT)
            explicit == null && name == "Specification" && module.starImports.any { it.packageName == SPOCK_PACKAGE } ->
                context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.STAR_IMPORT_ONLY)
            else -> uncertain(node)
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

    /** Classes whose full or package-relative name, with `$` normalised to `.`, equals [name]. */
    private fun moduleClasses(name: String): List<ClassNode> = module.classes.filter { candidate ->
        candidate.name.replace('$', '.') == name || candidate.nameWithoutPackage.replace('$', '.') == name
    }

    /** Classes nested directly in one of [node]'s enclosing classes with the bare [name]. */
    private fun enclosingNested(node: ClassNode, name: String): List<ClassNode> {
        val scopes = generateSequence(node.outerClass) { it.outerClass }.map { it.name + "$" + name }.toSet()
        return module.classes.filter { it.name in scopes }
    }

    private fun uncertain(node: ClassNode) =
        context(node, SpecificationStatus.UNCERTAIN, SpecificationEvidence.UNRESOLVED_SUPERCLASS)

    private fun context(node: ClassNode, status: SpecificationStatus, evidence: SpecificationEvidence) =
        SpockClassContext(node, status, evidence)
}
