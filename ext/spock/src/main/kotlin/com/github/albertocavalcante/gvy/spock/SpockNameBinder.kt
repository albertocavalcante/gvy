package com.github.albertocavalcante.gvy.spock

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.util.Collections
import java.util.IdentityHashMap

/** What a class-name spelling can be shown to bind to from one module's source alone. */
internal sealed interface NameBinding {
    /** A class declared in this module. */
    data class ModuleClass(val node: ClassNode) : NameBinding

    /** A dotted spelling that names no module class; taken as that fully qualified name. */
    data class Qualified(val name: String) : NameBinding

    /** A single-type import, possibly aliased. */
    data class Imported(val fqn: String) : NameBinding

    /**
     * Only a star import could supply the requested target; a same-package class or another star
     * import would take precedence, so this is never proof. Resolving it needs target evidence (#1285).
     */
    data class StarImportOnly(val fqn: String) : NameBinding

    /** No binding visible in the module (for example, an external class in the same package). */
    data object Unbound : NameBinding

    data class Ambiguous(val reason: Ambiguity) : NameBinding

    enum class Ambiguity {
        /** More than one module class or import binds the spelling. */
        MULTIPLE_BINDINGS,

        /** An enclosing class's superclass could supply a nested class with that name first. */
        INHERITED_NESTED_SCOPE,
    }
}

/**
 * Conservative Groovy name binding over one module, shared by the Spock source models.
 *
 * Every binding the module can see is collected (top-level and package-relative classes, classes
 * nested in an [enclosing][bind] class, single-type imports); more than one is ambiguous instead of
 * depending on Groovy's precedence. Never loads classes or reads a classpath.
 */
internal class SpockNameBinder(private val module: ModuleNode) {
    /**
     * Binds [spelling] (`$` or `.` separated) as seen from [enclosing] classes, innermost first.
     * [targets] are fully qualified names the caller asks about: they decide which star imports count
     * and are exempt from the inherited-scope rule when spelled exactly. With [checkInheritedScope],
     * any enclosing class declaring a non-Object superclass or an interface makes the spelling ambiguous,
     * because that supertype may declare a nested class of the same name that this module cannot see.
     */
    fun bind(
        spelling: String,
        enclosing: List<ClassNode>,
        targets: Set<String>,
        checkInheritedScope: Boolean = true,
    ): NameBinding {
        val name = spelling.replace('$', '.')
        val simple = '.' !in name
        val bases: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())
        bases.addAll(moduleClasses(name))
        if (simple) bases.addAll(nestedIn(enclosing, name))
        val explicit = if (simple) module.getImport(name)?.className else null
        return when {
            bases.size + (if (explicit != null) 1 else 0) > 1 ->
                NameBinding.Ambiguous(NameBinding.Ambiguity.MULTIPLE_BINDINGS)
            checkInheritedScope && name !in targets && enclosing.any(::inheritsScope) ->
                NameBinding.Ambiguous(NameBinding.Ambiguity.INHERITED_NESTED_SCOPE)
            bases.isNotEmpty() -> NameBinding.ModuleClass(bases.single())
            explicit != null -> NameBinding.Imported(explicit)
            !simple -> NameBinding.Qualified(name)
            else -> starCandidate(name, targets) ?: NameBinding.Unbound
        }
    }

    /** Full or package-relative names, with `$` normalised to `.`. */
    private fun moduleClasses(name: String): List<ClassNode> = module.classes.filter { candidate ->
        candidate.name.replace('$', '.') == name || candidate.nameWithoutPackage.replace('$', '.') == name
    }

    private fun nestedIn(enclosing: List<ClassNode>, name: String): List<ClassNode> {
        val scopes = enclosing.map { it.name + "$" + name }.toSet()
        return module.classes.filter { it.name in scopes }
    }

    /**
     * Any supertype that could contribute nested classes: a non-Object superclass or any interface
     * (Groovy 4.0.33 resolves bare names against nested classes of an enclosing class's interfaces).
     * By name, so a bare pre-resolution `Object` or an enum (extending `Enum`) also counts: conservative.
     */
    private fun inheritsScope(node: ClassNode): Boolean =
        (node.superClass != null && node.superClass.name != ClassHelper.OBJECT_TYPE.name) ||
            node.interfaces.orEmpty().isNotEmpty()

    private fun starCandidate(name: String, targets: Set<String>): NameBinding? {
        val packages = module.starImports.mapNotNull { it.packageName }.toSet()
        return targets.firstOrNull { target ->
            target.substringAfterLast('.') == name && target.substringBeforeLast('.') + "." in packages
        }?.let(NameBinding::StarImportOnly)
    }
}
