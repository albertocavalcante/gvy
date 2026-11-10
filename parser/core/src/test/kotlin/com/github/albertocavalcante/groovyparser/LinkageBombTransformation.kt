package com.github.albertocavalcante.groovyparser

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

/**
 * Test-only global transform (registered in META-INF/services) that fails like a transform whose dependency is
 * missing, but only for a class named `LinkageBomb`, so it is inert for every other test.
 */
@GroovyASTTransformation(phase = CompilePhase.CONVERSION)
class LinkageBombTransformation : ASTTransformation {
    override fun visit(nodes: Array<ASTNode>, source: SourceUnit) {
        if (source.ast.classes.any { it.name == "LinkageBomb" }) {
            throw NoClassDefFoundError("org/apache/ivy/Missing").apply {
                initCause(ClassNotFoundException("org.apache.ivy.Ivy"))
            }
        }
    }
}
