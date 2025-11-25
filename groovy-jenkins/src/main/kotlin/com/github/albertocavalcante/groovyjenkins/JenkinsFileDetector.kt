package com.github.albertocavalcante.groovyjenkins

import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Detects Jenkins pipeline files based on configured patterns.
 */
class JenkinsFileDetector(private val patterns: List<String> = listOf("Jenkinsfile")) {
    private val logger = LoggerFactory.getLogger(JenkinsFileDetector::class.java)
    private val compiledPatterns = patterns.map { compilePattern(it) }

    /**
     * Checks if the given URI represents a Jenkins pipeline file.
     */
    @Suppress("TooGenericExceptionCaught")
    fun isJenkinsFile(uri: URI): Boolean {
        if (patterns.isEmpty()) {
            return false
        }

        val path = try {
            Paths.get(uri)
        } catch (e: Exception) {
            logger.warn("Failed to convert URI to path: $uri", e)
            return false
        }

        return compiledPatterns.any { matcher ->
            matcher.matches(path)
        }
    }

    private fun compilePattern(pattern: String): PathMatcher = when {
        pattern.contains("*") || pattern.contains("?") -> {
            // Glob pattern
            GlobMatcher(pattern)
        }
        else -> {
            // Exact filename match
            FilenameMatcher(pattern)
        }
    }

    private interface PathMatcher {
        fun matches(path: Path): Boolean
    }

    private class FilenameMatcher(private val filename: String) : PathMatcher {
        override fun matches(path: Path): Boolean = path.fileName?.toString() == filename
    }

    private class GlobMatcher(pattern: String) : PathMatcher {
        @Suppress("TooGenericExceptionCaught")
        private val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            LoggerFactory.getLogger(GlobMatcher::class.java)
                .warn("Failed to compile glob pattern: $pattern", e)
            null
        }

        override fun matches(path: Path): Boolean {
            if (matcher == null) return false

            // Try matching the full path
            if (matcher.matches(path)) return true

            // Try matching just the filename
            val filename = path.fileName
            if (filename != null && matcher.matches(filename)) return true

            // Try matching relative path segments from the end
            // For pattern "pipelines/*.groovy" to match "/workspace/pipelines/deploy.groovy"
            val pathString = path.toString()
            val segments = pathString.split("[/\\\\]".toRegex())

            // Try different tail segments
            for (i in segments.indices) {
                val tail = segments.subList(i, segments.size).joinToString("/")
                val relativePath = Paths.get(tail)
                if (matcher.matches(relativePath)) return true
            }

            return false
        }
    }
}
