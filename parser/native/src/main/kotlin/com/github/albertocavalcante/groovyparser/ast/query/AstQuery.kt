package com.github.albertocavalcante.groovyparser.ast.query

data class AstQuery(val patterns: List<AstQueryPattern>) {
    companion object {
        fun parse(raw: String): AstQuery = AstQueryParser(raw).parse()
    }
}

data class AstQueryPattern(val type: String, val capture: String?, val children: List<AstQueryPattern>)
