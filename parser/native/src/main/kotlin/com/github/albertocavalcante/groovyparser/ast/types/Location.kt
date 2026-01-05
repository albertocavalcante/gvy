package com.github.albertocavalcante.groovyparser.ast.types

data class Position(val line: Int, val character: Int)

data class Range(val start: Position, val end: Position)
