// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.services.JvmClasspathReflection
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver

/** Semantic and reflective reads must use the compiler that produced the retained AST. */
internal class NativeQueryContext(val held: CompiledDocumentSnapshot, val members: HoverMemberLookup) {
    private val loader = bytecodeOnlyClassLoader(held.parseResult.compilationUnit.classLoader)
    val binaryScope = loader.context
    val reflection = JvmClasspathReflection { loader }
    val types = SemanticTypeResolver(ReflectionTypeSolver(jreOnly = false, classLoader = loader), members)
}
