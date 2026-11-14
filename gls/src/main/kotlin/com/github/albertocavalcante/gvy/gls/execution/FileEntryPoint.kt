// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode

data class FileEntryPoint(val id: String, val label: String, val line: Int)

/** Discover entry points from the compiler's syntax tree, never from text patterns. */
fun discoverEntryPoints(module: ModuleNode): List<FileEntryPoint> {
    val entries = mutableListOf<FileEntryPoint>()
    val stringIsShadowed = module.classes.any { it.nameWithoutPackage == "String" } ||
        module.imports.any { it.alias == "String" && it.type.name != "java.lang.String" }
    for (type in module.classes) {
        if (type.isScript) {
            entries.add(FileEntryPoint("script", "Script", 0))
            continue
        }
        for (method in type.methods) {
            if (isCompatibleMain(method, stringIsShadowed)) {
                entries.add(FileEntryPoint(type.name, type.name, (method.lineNumber - 1).coerceAtLeast(0)))
            }
        }
    }
    return entries.distinctBy { it.id }
}

private fun isCompatibleMain(method: MethodNode, stringIsShadowed: Boolean): Boolean {
    if (method.name != "main" || !method.isPublic || !method.isStatic) return false
    if (method.returnType.name != "void" || method.parameters.size != 1) return false
    val parameterType = method.parameters[0].type
    if (!parameterType.isArray) return false
    return when (parameterType.componentType.name) {
        "java.lang.String" -> true
        "String" -> !stringIsShadowed
        else -> false
    }
}
