// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

/** The `access_flags` of a class file (JVMS §4.1, Table 4.1-B); only the flags the class index filters on. */
@JvmInline
internal value class ClassAccessFlags(private val bits: Int) {
    val isPublic: Boolean get() = bits and PUBLIC != 0
    val isSynthetic: Boolean get() = bits and SYNTHETIC != 0
    val isModule: Boolean get() = bits and MODULE != 0

    /** Public, and neither compiler-generated nor a module descriptor. */
    val isIndexable: Boolean get() = isPublic && !isSynthetic && !isModule

    private companion object {
        const val PUBLIC = 0x0001
        const val SYNTHETIC = 0x1000
        const val MODULE = 0x8000
    }
}
