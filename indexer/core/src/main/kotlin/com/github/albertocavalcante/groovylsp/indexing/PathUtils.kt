package com.github.albertocavalcante.groovylsp.indexing

import java.io.File

object PathUtils {
    /**
     * Standardizes file URIs by ensuring they start with 'file:///' and removing
     * trailing slashes added by File.toURI() for directories.
     */
    fun toCanonicalUri(path: String): String {
        // Use absolute path and normalize to handle relative components like /..
        val pathObj = File(path).toPath().toAbsolutePath().normalize()
        val file = pathObj.toFile()
        val uri = file.toURI().toString()
        val tripleSlashUri = if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri.replaceFirst("file:/", "file:///")
        } else {
            uri
        }

        // Root directories (e.g. /, C:\) have no parent and must preserve trailing slash
        val isRoot = pathObj.parent == null
        return if (isRoot) {
            tripleSlashUri
        } else {
            tripleSlashUri.removeSuffix("/")
        }
    }
}
