// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast.query

import org.codehaus.groovy.ast.ASTNode

data class AstQueryMatch(val node: ASTNode, val captures: Map<String, ASTNode>)
