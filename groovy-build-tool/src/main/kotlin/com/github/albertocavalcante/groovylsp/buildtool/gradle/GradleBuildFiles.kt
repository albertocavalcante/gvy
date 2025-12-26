package com.github.albertocavalcante.groovylsp.buildtool.gradle

object GradleBuildFiles {
    val fileNames: Set<String> = linkedSetOf(
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
    )
}
