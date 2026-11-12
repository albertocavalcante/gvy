package com.github.albertocavalcante.gvy.launcher

/** The JVM owns all application command parsing, including the no-argument LSP mode. */
internal fun javaCommand(
    arguments: List<String>,
    javaHome: String?,
    jar: String,
    windows: Boolean = false,
): List<String> {
    val name = if (windows) "java.exe" else "java"
    val java = javaHome?.let {
        if (windows) "${it.trimEnd('/', '\\')}\\bin\\$name" else "${it.trimEnd('/')}/bin/$name"
    } ?: name
    return listOf(java, "-Xmx2g", "-jar", jar) + arguments
}

/** Quote one argument for the Windows C runtime / Java launcher, without invoking a shell. */
internal fun quoteWindowsArgument(argument: String): String = buildString {
    append('"')
    var backslashes = 0
    for (character in argument) {
        when (character) {
            '\\' -> backslashes++
            '"' -> {
                repeat(backslashes * 2 + 1) { append('\\') }
                append('"')
                backslashes = 0
            }
            else -> {
                repeat(backslashes) { append('\\') }
                append(character)
                backslashes = 0
            }
        }
    }
    repeat(backslashes * 2) { append('\\') }
    append('"')
}
