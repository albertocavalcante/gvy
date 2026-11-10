package com.github.albertocavalcante.gvy.gdsl

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import java.io.File

/**
 * Test-only global AST transformation, registered in META-INF/services for the test classpath. It
 * writes the file named by [MARKER_PROPERTY] whenever a compilation runs its CONVERSION phase
 * operations, so a test can prove that reading GDSL never runs compile phases.
 */
@GroovyASTTransformation(phase = CompilePhase.CONVERSION)
class MarkerTransform : ASTTransformation {
    override fun visit(nodes: Array<out ASTNode>?, source: SourceUnit?) {
        System.getProperty(MARKER_PROPERTY)?.let { File(it).writeText("fired") }
    }

    companion object {
        const val MARKER_PROPERTY = "gvy.gdsl.test.transformMarker"
    }
}
