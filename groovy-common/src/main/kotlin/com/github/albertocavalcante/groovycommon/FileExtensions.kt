package com.github.albertocavalcante.groovycommon

object FileExtensions {
    const val GROOVY = "groovy"
    const val GRADLE = "gradle"
    const val JAVA = "java"
    const val KOTLIN = "kt"
    const val KOTLIN_SCRIPT = "kts"
    const val JENKINSFILE = "Jenkinsfile"

    val ALL_GROOVY_LIKE = setOf(GROOVY, GRADLE, JENKINSFILE)
}
