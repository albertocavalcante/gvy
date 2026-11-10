package com.github.albertocavalcante.groovyparser.api

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer

/**
 * Compiler policy shared by every engine that compiles user code only to analyse it.
 *
 * Analysis must never execute code taken from the sources being analysed, nor reach for the network:
 * opening a workspace is not consent to run it.
 */
object AnalysisCompilerPolicy {
    /**
     * Global AST transformations that must never run during analysis, even when the compiler's own class
     * loader can see them.
     *
     * - Spock's transform rewrites feature methods and drops the block labels the language server reads.
     * - `@Grab` resolves dependencies through Ivy (not shipped with the server, so it throws
     *   `NoClassDefFoundError`) and would download artifacts just because a file was opened.
     */
    val DISABLED_GLOBAL_TRANSFORMS: Set<String> = setOf(
        "org.spockframework.compiler.SpockTransform",
        "groovy.grape.GrabAnnotationTransformation",
    )

    /**
     * Annotations whose transformation executes source text: `@ASTTest` compiles its closure and runs it
     * inside the compiler JVM (during SEMANTIC_ANALYSIS by default), which is arbitrary code execution for
     * anyone who can put a file into the workspace. They are removed before the compiler collects local
     * transforms; the annotation itself is not needed to analyse the code.
     */
    val CODE_EXECUTING_ANNOTATIONS: Set<String> = setOf("groovy.transform.ASTTest")

    /** Applies the policy to [config]: disabled global transforms plus the annotation stripper. */
    fun apply(config: CompilerConfiguration) {
        config.disabledGlobalASTTransformations = DISABLED_GLOBAL_TRANSFORMS
        config.addCompilationCustomizers(CodeExecutingAnnotationStripper())
    }
}

/**
 * Removes [AnalysisCompilerPolicy.CODE_EXECUTING_ANNOTATIONS] from every annotated node of a class at
 * CONVERSION, i.e. before SEMANTIC_ANALYSIS collects local transforms. Matches the fully qualified name, the
 * simple name (an unresolved reference or star import) and any `import ... as Alias`.
 *
 * `@AnnotationCollector([ASTTest]) @interface Alias` would reintroduce the annotation when `@Alias` is
 * expanded at SEMANTIC_ANALYSIS. Every collector is therefore sanitised at its own declaration: the blocked
 * entries are removed from its `value` list, so no use of it (in this file, another workspace source, or a
 * collector that collects it) can expand into a blocked annotation. Its own `@ASTTest` annotations are removed
 * like any other.
 */
internal class CodeExecutingAnnotationStripper : CompilationCustomizer(CompilePhase.CONVERSION) {
    @Throws(CompilationFailedException::class)
    override fun call(source: SourceUnit, context: GeneratorContext, classNode: ClassNode) {
        val names = blockedNames(classNode)
        if (classNode.isAnnotationDefinition) sanitizeCollector(classNode, names)
        object : ClassCodeVisitorSupport() {
            override fun getSourceUnit(): SourceUnit = source

            override fun visitAnnotations(node: AnnotatedNode) {
                node.annotations
                    .filter { annotation: AnnotationNode -> annotation.classNode.name in names }
                    .forEach { node.annotations.remove(it) }
                super.visitAnnotations(node)
            }
        }.visitClass(classNode)
    }

    private fun blockedNames(classNode: ClassNode): Set<String> =
        namesFor(classNode, AnalysisCompilerPolicy.CODE_EXECUTING_ANNOTATIONS)

    /** Qualified names, simple names and `import ... as Alias` aliases of [qualified] as seen from [classNode]. */
    private fun namesFor(classNode: ClassNode, qualified: Set<String>): Set<String> {
        val names = HashSet<String>(qualified)
        qualified.mapTo(names) { it.substringAfterLast('.') }
        classNode.module?.imports?.forEach { import ->
            if (import.className in qualified && import.alias != null) names.add(import.alias)
        }
        return names
    }

    /**
     * Neutralises an `@AnnotationCollector` declaration. Besides removing blocked entries from `value`, the
     * compiler evaluates the `mode` and `processor` members when it expands a use of the collector: a
     * non-constant expression is compiled and run. So `processor` and `serializeClass` are always dropped and
     * `mode` is kept only when it is a plain `AnnotationCollectorMode.<CONSTANT>`.
     */
    private fun sanitizeCollector(collector: ClassNode, blocked: Set<String>) {
        val collectorNames = namesFor(collector, setOf(COLLECTOR))
        collector.annotations
            .filter { it.classNode.name in collectorNames }
            .forEach { annotation ->
                val value = annotation.getMember("value")
                if (value is ListExpression) {
                    value.expressions.removeAll { entryName(it) in blocked }
                } else if (value != null && entryName(value) in blocked) {
                    annotation.members.remove("value")
                }
                annotation.members.remove("processor")
                annotation.members.remove("serializeClass")
                val mode = annotation.getMember("mode")
                if (mode != null && !isPlainModeConstant(mode)) annotation.members.remove("mode")
            }
    }

    private fun isPlainModeConstant(mode: Expression): Boolean {
        val property = mode as? PropertyExpression ?: return false
        val owner = property.objectExpression.text
        val constant = property.propertyAsString
        return owner in MODE_ENUMS && constant in MODE_CONSTANTS && !property.isSafe && !property.isSpreadSafe
    }

    /**
     * Name written for a collected annotation. Before resolution the entry is a variable or property
     * expression, afterwards a class expression; `Name.class` is a property expression ending in `.class`.
     */
    private fun entryName(entry: Expression): String = entry.text.removeSuffix(".class")

    private companion object {
        const val COLLECTOR = "groovy.transform.AnnotationCollector"
        val MODE_ENUMS = setOf("AnnotationCollectorMode", "groovy.transform.AnnotationCollectorMode")
        val MODE_CONSTANTS = setOf(
            "DUPLICATE",
            "PREFER_COLLECTOR",
            "PREFER_COLLECTOR_MERGED",
            "PREFER_EXPLICIT",
            "PREFER_EXPLICIT_MERGED",
        )
    }
}
