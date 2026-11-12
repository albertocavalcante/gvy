// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast.types

data class Position(val line: Int, val character: Int)

data class Range(val start: Position, val end: Position)
