package com.github.albertocavalcante.gvy.build

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Utility for locating test resources in both Gradle and Bazel environments.
 *
 * In Gradle, resources are at: src/test/resources/...
 * In Bazel, resources are in runfiles at: build-tool/src/test/resources/...
 */
object TestResources {

    /**
     * Get the path to a test resource directory.
     * Works in both Gradle (relative path) and Bazel (runfiles) environments.
     *
     * @param resourceDir The resource directory name under src/test/resources/
     * @return Path to the resource directory
     */
    fun getResourcePath(resourceDir: String): Path {
        // Try multiple locations in order of preference
        val candidates = listOf(
            // Gradle: running from module directory
            "src/test/resources/$resourceDir",
            // Gradle: running from project root
            "build-tool/src/test/resources/$resourceDir",
            // Bazel: runfiles with workspace prefix
            System.getenv("TEST_SRCDIR")?.let { "$it/_main/build-tool/src/test/resources/$resourceDir" },
            // Bazel: runfiles without workspace prefix
            System.getenv("TEST_SRCDIR")?.let { "$it/build-tool/src/test/resources/$resourceDir" },
        )

        for (candidate in candidates.filterNotNull()) {
            val path = Paths.get(candidate)
            if (Files.exists(path)) {
                return path
            }
        }

        throw IllegalStateException(
            "Test resource not found: $resourceDir. Searched: ${candidates.filterNotNull().joinToString()}",
        )
    }

    /**
     * Get the path to the test-gradle-project fixture.
     */
    fun getTestGradleProject(): Path = getResourcePath("test-gradle-project")

    /**
     * Get the path to the test-maven-project fixture.
     */
    fun getTestMavenProject(): Path = getResourcePath("test-maven-project")

    /**
     * Get the path to the non-gradle-project fixture.
     */
    fun getNonGradleProject(): Path = getResourcePath("non-gradle-project")
}
