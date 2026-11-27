// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserRange
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.transform.AnnotationCollector
import groovy.transform.AnnotationCollectorMode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.decompiled.AsmDecompiler
import org.codehaus.groovy.ast.decompiled.AsmReferenceResolver
import org.codehaus.groovy.ast.decompiled.ClassStub
import org.codehaus.groovy.ast.decompiled.DecompiledClassNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.control.ClassNodeResolver
import org.codehaus.groovy.control.CompilationUnit
import java.io.IOException

/**
 * Applies the analysis policy to classes the compiler reads from class files.
 *
 * The policy for sources ([com.github.albertocavalcante.groovyparser.api.AnalysisCompilerPolicy]) runs as a compile
 * customizer, so it only ever sees classes being compiled. A class that arrives precompiled on the project class
 * path reaches the compiler through this resolver instead, and is replaced here by an [AnalysisClassNode] that
 * applies the same rules.
 */
internal open class AnalysisClassNodeResolver : ClassNodeResolver() {
    private val unexpandedCollectors = LinkedHashSet<String>()

    override fun findClassNode(name: String, compilationUnit: CompilationUnit): LookupResult? {
        val found = super.findClassNode(name, compilationUnit)
        val node = found?.takeIf { it.isClassNode }?.classNode
        if (node !is DecompiledClassNode || node is AnalysisClassNode) return found
        // Read again what the compiler just read (it keeps it, so this is a lookup) to build the policy's node.
        // A class that cannot be read here is left unresolved: handing back the unrestricted node would skip
        // the policy.
        val stub = readStub(name, compilationUnit) ?: return null
        val references = AsmReferenceResolver(this, compilationUnit)
        return LookupResult(null, AnalysisClassNode(stub, references, unexpandedCollectors::add))
    }

    private fun readStub(name: String, compilationUnit: CompilationUnit): ClassStub? {
        val resource = compilationUnit.classLoader.getResource(name.replace('.', '/') + ".class") ?: return null
        return try {
            AsmDecompiler.parseClass(resource)
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** Installs the policy's resolver on [unit]. */
        fun install(unit: CompilationUnit) {
            unit.classNodeResolver = AnalysisClassNodeResolver()
        }

        /** Project annotation collectors [unit] met and did not expand, in the order it met them. */
        fun unexpandedCollectorsOf(unit: CompilationUnit): List<String> =
            (unit.classNodeResolver as? AnalysisClassNodeResolver)?.unexpandedCollectors?.toList().orEmpty()
    }
}

/** Diagnostic code of the notice for a project-jar annotation collector that analysis does not expand. */
internal const val PROJECT_COLLECTOR_NOT_EXPANDED = "project-collector-not-expanded"

/**
 * Not an error in the user's code: expanding a collector from a project jar means running that jar's code, which the
 * server never does. The annotations it stands for are missing from the analysed class, so the notice says so;
 * staying silent would make the result look complete.
 */
internal fun projectCollectorNotice(collector: String): ParserDiagnostic = ParserDiagnostic(
    range = ParserRange.point(0, 0),
    severity = ParserSeverity.INFORMATION,
    message = "Annotation collector $collector comes from the project classpath and is not expanded during " +
        "analysis; the annotations it stands for are not applied",
    source = "groovy-parser",
    code = PROJECT_COLLECTOR_NOT_EXPANDED,
)

/**
 * A class read from a class file, with the analysis policy applied to its `@AnnotationCollector` declaration.
 *
 * To expand a use of a precompiled collector the compiler loads the collector's data class and invokes its static
 * `value()` method. That request goes through the compile class loader, which sees project jars, so a jar's code
 * would run in the analysing process. Only a data class the server itself supplies is used: Groovy's own
 * collectors keep expanding, with the server's data and never a copy from the project class path, and any other
 * collector expands to nothing and is reported through [onUnexpandedCollector].
 *
 * The compiler also evaluates the collector's `mode`. Read from a class file it is `AnnotationCollectorMode.NAME`,
 * which the compiler evaluates by generating a class and running it; the enum constant itself is evaluated as is.
 */
internal class AnalysisClassNode(
    stub: ClassStub,
    references: AsmReferenceResolver,
    private val onUnexpandedCollector: (String) -> Unit,
) : DecompiledClassNode(stub, references) {
    override fun getAnnotations(): MutableList<AnnotationNode> = super.getAnnotations().also(::applyCollectorPolicy)

    override fun getAnnotations(type: ClassNode): MutableList<AnnotationNode> =
        super.getAnnotations(type).also(::applyCollectorPolicy)

    private fun applyCollectorPolicy(annotations: List<AnnotationNode>) {
        for (annotation in annotations) {
            val isCollector = annotation.classNode.name == COLLECTOR
            if (isCollector && annotation.getNodeMetaData<Any>(AnalysisClassNode::class.java) == null) {
                annotation.setNodeMetaData(AnalysisClassNode::class.java, true)
                useConstantMode(annotation)
                useServerDataOnly(annotation)
            }
        }
    }

    private fun useConstantMode(annotation: AnnotationNode) {
        val mode = annotation.getMember(MODE) ?: return
        val constant = plainModeConstant(mode)
        if (constant == null) {
            annotation.members.remove(MODE)
        } else {
            annotation.setMember(MODE, ConstantExpression(constant))
        }
    }

    private fun plainModeConstant(mode: Expression): AnnotationCollectorMode? {
        val property = mode as? PropertyExpression ?: return null
        val owner = property.objectExpression as? ClassExpression ?: return null
        if (owner.type.name != AnnotationCollectorMode::class.java.name) return null
        return enumValues<AnnotationCollectorMode>().firstOrNull { it.name == property.propertyAsString }
    }

    private fun useServerDataOnly(annotation: AnnotationNode) {
        // Without the member, or with its default, the compiler takes the data from the collector class itself.
        val declared = (annotation.getMember(DATA_CLASS) as? ClassExpression)?.type?.name
        val dataClass = declared?.takeUnless { it == COLLECTOR } ?: name
        val serverData = serverCollectorData(dataClass)
        if (serverData == null) onUnexpandedCollector(name)
        val data = serverData ?: NoCollectorData::class.java
        annotation.setMember(DATA_CLASS, ClassExpression(ClassHelper.make(data)))
    }

    /** The data class [name] as the server's own Groovy supplies it, or null for any other class. */
    private fun serverCollectorData(name: String): Class<*>? {
        if (GROOVY_PACKAGES.none(name::startsWith)) return null
        return try {
            Class.forName(name, false, AnalysisClassNode::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private companion object {
        val COLLECTOR: String = AnnotationCollector::class.java.name
        const val MODE = "mode"
        const val DATA_CLASS = "serializeClass"
        val GROOVY_PACKAGES = listOf("groovy.", "org.codehaus.groovy.", "org.apache.groovy.")
    }
}

/** The data of a collector that analysis does not expand: it collects nothing. The compiler calls [value]. */
internal object NoCollectorData {
    @JvmStatic
    fun value(): Array<Array<Any?>> = emptyArray()
}
