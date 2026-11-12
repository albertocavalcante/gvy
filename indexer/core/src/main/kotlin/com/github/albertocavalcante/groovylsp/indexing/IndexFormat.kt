// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovylsp.indexing

enum class IndexFormat {
    SCIP,
    LSIF,
    ;

    companion object {
        fun fromString(value: String): IndexFormat? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}
