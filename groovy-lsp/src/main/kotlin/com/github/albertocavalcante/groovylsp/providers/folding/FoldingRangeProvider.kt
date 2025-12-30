package com.github.albertocavalcante.groovylsp.providers.folding

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.ast.safeRange
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import java.net.URI

class FoldingRangeProvider(private val compilationService: GroovyCompilationService) {
    fun provideFoldingRanges(uri: URI): List<FoldingRange> {
        val astModel = compilationService.getAstModel(uri) ?: return emptyList()
        val foldingRanges = mutableListOf<FoldingRange>()

        // Add proper imports here if getAllNodes returns them
        // For now, iterate directly for classes and methods using helper if available or traverse
        // Since GroovyAstModel.getAllClassNodes() is available:
        astModel.getAllClassNodes().forEach { classNode ->
            addClassFoldingRange(classNode, foldingRanges)
            classNode.methods.forEach { methodNode ->
                addMethodFoldingRange(methodNode, foldingRanges)
            }
        }

        return foldingRanges
    }

    private fun addClassFoldingRange(classNode: ClassNode, ranges: MutableList<FoldingRange>) {
        if (classNode.lineNumber > 0 && classNode.lastLineNumber > classNode.lineNumber) {
            // LSP uses 0-based lines, Groovy uses 1-based
            // We want to fold from the line of declaration to the closing brace
            // However, clients often want to see the declaration line.
            // Usually startLine is where the folding starts (the line to stay visible is the one above it? No).
            // LSP: "The content starting at startLine is folded."
            // Actually, usually we want to fold the *body*.
            // Valid ranges:
            // startLine: 0-based.
            // endLine: 0-based.

            // For a class:
            // class Foo {  <-- line 0
            //   ...
            // }            <-- line N

            // We return startLine=0, endLine=N-1 (to exclude closing brace) or N?
            // The test expects endLine=3 for a class ending on line 4. So N-1.

            val startLine = classNode.lineNumber - 1
            val endLine = classNode.lastLineNumber - 1 - 1 // Exclude closing brace?
            // Test says: closing brace is line 4. Expect endLine 3.
            // Groovy lastLineNumber = 5 (1-based)? No, lines are 1-based.
            // Line 4 in file is line 4.
            // 1-based: 4.
            // 0-based: 3.
            // If we want endLine 3, that corresponds to 0-based line 3.
            // So simply convert 1-based lastLineNumber to 0-based.
            // 4 -> 3.
            // Wait, the test comment says: "endLine 3 excludes closing brace (line 4)".
            // If line 4 (0-based) is the brace...
            // The file content:
            // 34: }
            // 35: }
            // Wait, let's look at the file content again.
            // 31: class FoldingTest {
            // 35: }
            // Lines: 31, 32, 33, 34, 35. (Lines in snippet 1-based)
            // In the snippet provided in test yaml:
            // 0: class FoldingTest {
            // 4: }
            // If 1-based:
            // 1: class...
            // 5: }
            // Groovy parser likely returns lineNumber=1, lastLineNumber=5.
            // 0-based: start=0, end=4.
            // The test expects endLine=3.
            // So we must subtract 1 from the 0-based end line?
            // i.e. endLine = (lastLineNumber - 1) - 1.

            val computedEndLine = classNode.lastLineNumber - 1 - 1

            if (computedEndLine > startLine) {
                ranges.add(
                    FoldingRange(startLine, computedEndLine).apply {
                        kind = FoldingRangeKind.Region
                    },
                )
            }
        }
    }

    private fun addMethodFoldingRange(methodNode: MethodNode, ranges: MutableList<FoldingRange>) {
        if (methodNode.lineNumber > 0 && methodNode.lastLineNumber > methodNode.lineNumber) {
            val startLine = methodNode.lineNumber - 1
            // Same logic for method?
            // Test: method ends at line 3 (0-based).
            // Brace is at line 3.
            // Test expects endLine 2.
            // 3 - 1 = 2.
            // So logic holds: (lastLineNumber - 1) - 1.

            val computedEndLine = methodNode.lastLineNumber - 1 - 1

            if (computedEndLine > startLine) {
                ranges.add(
                    FoldingRange(startLine, computedEndLine).apply {
                        kind = FoldingRangeKind.Region
                    },
                )
            }
        }
    }
}
