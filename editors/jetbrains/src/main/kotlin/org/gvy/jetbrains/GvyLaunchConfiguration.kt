// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.jar.JarFile

internal data class GvyLaunchConfiguration(
    val command: List<String>,
    val initializationOptions: JsonObject,
) {
    companion object {
        fun from(options: GvyProjectSettings.Options): GvyLaunchConfiguration {
            val java = localFile(options.javaExecutable, "Java executable")
            require(Files.isExecutable(java)) { "Select an executable local Java binary." }
            val jar = localFile(options.serverJar, "GVY server JAR")
            JarFile(jar.toFile()).use {
                require(
                    !it.manifest
                        ?.mainAttributes
                        ?.getValue("Main-Class")
                        .isNullOrBlank(),
                ) {
                    "Select a runnable GVY server JAR with a Main-Class manifest entry."
                }
            }
            val arguments =
                options.jvmArguments
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
            for (argument in arguments) {
                require(argument.startsWith('-') && argument !in launchSelectors) {
                    "Use one complete JVM option per line; launch selectors and argument files are not supported."
                }
                require(launchSelectors.none { argument.startsWith("$it=") }) {
                    "JVM options cannot select a different main class, module or JAR."
                }
            }
            val configuration = JsonParser.parseString(options.serverConfiguration)
            require(configuration.isJsonObject) {
                "Server configuration must be a JSON object of canonical groovy.* keys."
            }
            require(configuration.asJsonObject.keySet().all { it.startsWith("groovy.") }) {
                "Use canonical groovy.* configuration keys."
            }
            return GvyLaunchConfiguration(
                listOf(java.toString()) + arguments + listOf("-jar", jar.toString(), "lsp", "stdio"),
                configuration.asJsonObject,
            )
        }

        private val launchSelectors =
            setOf(
                "-jar",
                "-m",
                "--module",
                "-cp",
                "-classpath",
                "--class-path",
                "-p",
                "--module-path",
                "--",
            )

        private fun localFile(
            value: String,
            label: String,
        ): Path {
            val path =
                try {
                    Path.of(value)
                } catch (_: InvalidPathException) {
                    throw IllegalArgumentException("$label must be a valid absolute local path.")
                }
            require(path.isAbsolute && Files.isRegularFile(path) && Files.isReadable(path)) {
                "$label must be a readable absolute local file."
            }
            return path.toRealPath()
        }
    }
}

internal fun canStartGvy(
    enabled: Boolean,
    trusted: Boolean,
    local: Boolean,
    extension: String?,
): Boolean = enabled && trusted && local && extension == "groovy"
