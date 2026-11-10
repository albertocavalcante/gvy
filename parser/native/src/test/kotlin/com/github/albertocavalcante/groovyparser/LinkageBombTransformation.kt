package com.github.albertocavalcante.groovyparser

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

/**
 * Test-only global transform (registered in META-INF/services) that fails like a transform whose
 * dependency is missing, but only for classes named `LinkageBomb` (missing class) and `FailsWithoutErrors`
 * (failed compile with an empty error collector), so it is inert for every other test.
 */
@GroovyASTTransformation(phase = CompilePhase.CONVERSION)
class LinkageBombTransformation : ASTTransformation {
    override fun visit(nodes: Array<ASTNode>, source: SourceUnit) {
        if (source.ast.classes.any { it.name == "LinkageBomb" }) {
            throw NoClassDefFoundError("org/apache/ivy/Missing")
        }
        if (source.ast.classes.any { it.name == "FailsWithoutErrors" }) {
            // Fails the compile without adding anything to the error collector
            throw CompilationFailedException(Phases.CONVERSION, source)
        }
    }
}
