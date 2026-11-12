// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.every
import java.net.URI

/** Answers the class-name lookup of a mocked service from the same index its full scan returns. */
internal fun stubClassLookup(service: GroovyCompilationService, uri: URI? = null, index: SymbolIndex? = null) {
    every { service.symbolIndexingService.findClasses(any()) } answers {
        val query = firstArg<ClassNameQuery>()
        if (uri == null || index == null) {
            emptyList()
        } else {
            index.getSymbols(uri).filterIsInstance<Symbol.Class>().filter(query::matches)
        }
    }
}
