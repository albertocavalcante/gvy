package com.github.albertocavalcante.groovyparser.ast

internal object CommentCloner {
    fun cloneLineComment(node: LineComment): LineComment =
        LineComment(node.content, CloningUtils.cloneRange(node.range))

    fun cloneBlockComment(node: BlockComment): BlockComment =
        BlockComment(node.content, CloningUtils.cloneRange(node.range))

    fun cloneJavadocComment(node: JavadocComment): JavadocComment =
        JavadocComment(node.content, CloningUtils.cloneRange(node.range))
}
