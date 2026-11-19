// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseResult

/** Owns only an unpublished compile; recovery retires it after all required observations are detached. */
internal class ParseResultOwner(initial: ParseResult) : AutoCloseable {
    var result: ParseResult = initial
        private set
    private var owned = true

    fun replaceWith(compile: () -> ParseResult): ParseResult {
        check(owned)
        // Mark retired first: a close or replacement failure must never close the old loader twice.
        owned = false
        result.compilationUnit.classLoader.close()
        return compile().also {
            result = it
            owned = true
        }
    }

    fun publish(value: ParseResult = result): ParseResult {
        check(owned && value.compilationUnit === result.compilationUnit)
        owned = false
        return value
    }

    override fun close() {
        if (owned) {
            owned = false
            result.compilationUnit.classLoader.close()
        }
    }
}
