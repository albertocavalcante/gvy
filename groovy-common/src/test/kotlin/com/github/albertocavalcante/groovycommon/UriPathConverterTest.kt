package com.github.albertocavalcante.groovycommon

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("UriPathConverter")
class UriPathConverterTest {

    @Test
    fun `toPath returns Path for valid file URI`() {
        val uri = URI.create("file:///workspace/Jenkinsfile")
        val result = UriPathConverter.toPath(uri)
        assertNotNull(result, "Should return Path for file:// URI")
    }

    @Test
    fun `toPath returns null for untitled URI scheme`() {
        val uri = URI.create("untitled:Untitled-1")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for untitled: URI")
    }

    @Test
    fun `toPath returns null for vscode-notebook URI scheme`() {
        val uri = URI.create("vscode-notebook-cell://path/to/notebook.ipynb")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for vscode-notebook-cell: URI")
    }

    @Test
    fun `toPath returns null for vscode-vfs URI scheme`() {
        val uri = URI.create("vscode-vfs://github/owner/repo/file.groovy")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for vscode-vfs: URI")
    }

    @Test
    fun `toPath returns null for http URI scheme`() {
        val uri = URI.create("http://example.com/file.groovy")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for http: URI")
    }

    @Test
    fun `toPath returns null for https URI scheme`() {
        val uri = URI.create("https://example.com/file.groovy")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for https: URI")
    }

    @Test
    fun `toPath returns null for jar URI scheme`() {
        val uri = URI.create("jar:file:///path/to/archive.jar!/entry.class")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for jar: URI")
    }

    @Test
    fun `toPath returns null for jrt URI scheme`() {
        val uri = URI.create("jrt:/java.base/java/lang/String.class")
        val result = UriPathConverter.toPath(uri)
        assertNull(result, "Should return null for jrt: URI")
    }

    @Test
    fun `isFileUri returns true for file scheme`() {
        val uri = URI.create("file:///workspace/file.groovy")
        assertTrue(UriPathConverter.isFileUri(uri), "Should return true for file: URI")
    }

    @Test
    fun `isFileUri returns false for untitled scheme`() {
        val uri = URI.create("untitled:Untitled-1")
        assertFalse(UriPathConverter.isFileUri(uri), "Should return false for untitled: URI")
    }

    @Test
    fun `isFileUri returns false for null scheme`() {
        // URI with no scheme (relative path)
        val uri = URI.create("/absolute/path")
        assertFalse(UriPathConverter.isFileUri(uri), "Should return false when scheme is null")
    }

    @Test
    fun `isFileUri returns false for http scheme`() {
        val uri = URI.create("http://example.com/file.groovy")
        assertFalse(UriPathConverter.isFileUri(uri), "Should return false for http: URI")
    }

    @Test
    fun `toPath handles file URI with spaces`() {
        val uri = URI.create("file:///workspace/my%20project/file.groovy")
        val result = UriPathConverter.toPath(uri)
        assertNotNull(result, "Should handle file URIs with encoded spaces")
    }

    @Test
    fun `toPath handles file URI with unicode`() {
        val uri = URI.create("file:///workspace/%E4%B8%AD%E6%96%87/file.groovy")
        val result = UriPathConverter.toPath(uri)
        assertNotNull(result, "Should handle file URIs with encoded unicode")
    }
}
