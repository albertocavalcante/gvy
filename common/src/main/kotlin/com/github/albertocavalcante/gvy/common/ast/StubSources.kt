package com.github.albertocavalcante.gvy.common.ast

/**
 * Prefix of the source unit name of a synthetic stub for a class that cannot be resolved. The parser
 * compiles an empty stub per missing class so Groovy keeps resolving everything else; the stub is
 * not a file the user wrote and must never surface in symbols, definitions or diagnostics.
 */
const val STUB_SOURCE_PREFIX = "gvy-stub:"

/** The source unit name of the stub that declares [fqn]. */
fun stubSourceName(fqn: String): String = STUB_SOURCE_PREFIX + fqn.replace('.', '/') + ".groovy"

/** True when [name] is the source unit name of a synthetic stub (see [STUB_SOURCE_PREFIX]). */
fun isStubSourceName(name: String?): Boolean = name != null && name.startsWith(STUB_SOURCE_PREFIX)
