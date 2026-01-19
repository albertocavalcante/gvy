package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
import com.github.albertocavalcante.groovyparser.errors.GroovyParserResult
import com.github.albertocavalcante.groovyparser.errors.toGroovyParserResult
import com.github.albertocavalcante.gvy.gls.providers.hover.DeclarationNodeRenderer.renderClassNode
import com.github.albertocavalcante.gvy.gls.providers.hover.DeclarationNodeRenderer.renderFieldNode
import com.github.albertocavalcante.gvy.gls.providers.hover.DeclarationNodeRenderer.renderMethodNode
import com.github.albertocavalcante.gvy.gls.providers.hover.DeclarationNodeRenderer.renderParameter
import com.github.albertocavalcante.gvy.gls.providers.hover.DeclarationNodeRenderer.renderPropertyNode
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderBinaryExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderClosureExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderConstantExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderDeclarationExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderGStringExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderListExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderMapExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderPropertyExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.ExpressionNodeRenderer.renderVariableExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.MetadataNodeRenderer.renderAnnotationNode
import com.github.albertocavalcante.gvy.gls.providers.hover.MetadataNodeRenderer.renderImportNode
import com.github.albertocavalcante.gvy.gls.providers.hover.MetadataNodeRenderer.renderPackageNode
import com.github.albertocavalcante.gvy.gls.providers.hover.MethodCallDisplayFormatter.renderConstructorCallExpression
import com.github.albertocavalcante.gvy.gls.providers.hover.MethodCallDisplayFormatter.renderMethodCallExpression
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Service to generate hover content for AST nodes using SemanticTypeResolver.
 * Delegates rendering to specialized renderer objects for different node types.
 */
class HoverContentGenerator(
    private val semanticResolver: SemanticTypeResolver,
    private val methodCallMetadataResolver: MethodCallMetadataResolver? = null,
) {

    /**
     * Generate hover for an AST node.
     */
    fun generateHover(node: ASTNode, moduleNode: ModuleNode? = null): GroovyParserResult<Hover> {
        val content = markdown {
            renderNode(node, moduleNode)
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = content
        }

        return Hover().apply {
            contents = Either.forRight(markupContent)
        }.toGroovyParserResult()
    }

    private fun MarkdownBuilder.renderNode(node: ASTNode, moduleNode: ModuleNode?) {
        when {
            node.isDeclarationNode() -> renderDeclarationNode(node, moduleNode)
            node.isExpressionNode() -> renderExpressionNode(node, moduleNode)
            node.isMetadataNode() -> renderMetadataNode(node)
            else -> defaultRender(node)
        }
    }

    @Suppress("UNUSED_PARAMETER") // moduleNode reserved for future semantic resolution in properties/fields
    private fun MarkdownBuilder.renderDeclarationNode(node: ASTNode, moduleNode: ModuleNode?) = when (node) {
        is MethodNode -> renderMethodNode(node)
        is ClassNode -> renderClassNode(node)
        is FieldNode -> renderFieldNode(node)
        is PropertyNode -> renderPropertyNode(node)
        is Parameter -> renderParameter(node)
        else -> defaultRender(node)
    }

    private fun MarkdownBuilder.renderExpressionNode(node: ASTNode, moduleNode: ModuleNode?) = when (node) {
        is VariableExpression -> renderVariableExpression(node, moduleNode, semanticResolver)
        is DeclarationExpression -> renderDeclarationExpression(node, moduleNode, semanticResolver)
        is MethodCallExpression -> renderMethodCallExpression(node, moduleNode, methodCallMetadataResolver)
        is ConstructorCallExpression -> renderConstructorCallExpression(node)
        is PropertyExpression -> renderPropertyExpression(node, moduleNode)
        is BinaryExpression -> renderBinaryExpression(node)
        is ClosureExpression -> renderClosureExpression(node)
        is ListExpression -> renderListExpression(node)
        is MapExpression -> renderMapExpression(node)
        is ConstantExpression -> renderConstantExpression(node)
        is GStringExpression -> renderGStringExpression(node)
        else -> defaultRender(node)
    }

    private fun MarkdownBuilder.renderMetadataNode(node: ASTNode) = when (node) {
        is ImportNode -> renderImportNode(node)
        is PackageNode -> renderPackageNode(node)
        is AnnotationNode -> renderAnnotationNode(node)
        else -> defaultRender(node)
    }

    private fun MarkdownBuilder.defaultRender(node: ASTNode) {
        section("AST Node") {
            text(node::class.java.simpleName)
            code { node.toString() }
        }
    }

    // Node Classification Helpers
    private fun ASTNode.isDeclarationNode(): Boolean = this is MethodNode || this is ClassNode ||
        this is FieldNode || this is PropertyNode || this is Parameter

    private fun ASTNode.isExpressionNode(): Boolean = this is VariableExpression || this is MethodCallExpression ||
        this is ConstructorCallExpression || this is PropertyExpression || this is BinaryExpression ||
        this is DeclarationExpression || this is ClosureExpression || this is ListExpression ||
        this is MapExpression || this is ConstantExpression || this is GStringExpression

    private fun ASTNode.isMetadataNode(): Boolean = this is ImportNode || this is PackageNode || this is AnnotationNode
}
