// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

/** Hosted scripts must run through their host, not as ordinary Groovy programs. */
fun hostedDslResponse(fileName: String): FileExecutionResponse? {
    if (listOf(".gradle", ".gradle.kts").any(fileName::endsWith)) {
        return FileExecutionResponse(
            kind = "unavailable",
            reason = "Gradle scripts require Gradle. Use Gradle: Select and Run Task.",
            alternativeCommand = "groovy.gradle.selectTask",
        )
    }
    if (fileName.endsWith(".gdsl")) {
        return FileExecutionResponse(
            kind = "unavailable",
            reason = "GDSL files provide IDE metadata. Configure groovy.jenkins.gdslPaths and " +
                "groovy.jenkins.gdslExecution.enabled in user settings when needed.",
        )
    }
    val jenkinsPrefix = listOf("Jenkinsfile.", "Jenkinsfile-", "Jenkinsfile_").any(fileName::startsWith)
    val isJenkins = fileName == "Jenkinsfile" || jenkinsPrefix || fileName.endsWith(".Jenkinsfile")
    return if (isJenkins) {
        FileExecutionResponse(
            "unavailable",
            reason = "Jenkinsfiles require Jenkins. Run this pipeline through its Jenkins job.",
        )
    } else {
        null
    }
}
