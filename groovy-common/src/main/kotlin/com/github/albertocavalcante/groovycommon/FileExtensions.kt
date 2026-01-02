package com.github.albertocavalcante.groovycommon

object FileExtensions {
    const val GROOVY = "groovy"
    const val GRADLE = "gradle"
    const val JAVA = "java"
    const val KOTLIN = "kt"
    const val KOTLIN_SCRIPT = "kts"
    const val JENKINSFILE = "Jenkinsfile"

    val EXTENSIONS: Set<String> = setOf(GROOVY, GRADLE)
    val FILENAMES: Set<String> = setOf(JENKINSFILE)
    val ALL_GROOVY_LIKE: Set<String> = EXTENSIONS + FILENAMES
}
