package com.github.albertocavalcante.gvy.launcher

/** The JVM owns all application command parsing, including the no-argument LSP mode. */
internal fun javaCommand(arguments: List<String>, javaHome: String?, jar: String): List<String> {
    val java = javaHome?.let { "${it.trimEnd('/')}/bin/java" } ?: "java"
    return listOf(java, "-Xmx2g", "-jar", jar) + arguments
}
