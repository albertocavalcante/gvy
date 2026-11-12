// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.Range

internal object CloningUtils {
    fun cloneRange(range: Range?): Range? = range?.let { Range(it.begin, it.end) }
}
