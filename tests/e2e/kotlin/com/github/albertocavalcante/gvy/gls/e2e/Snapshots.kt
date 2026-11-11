package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path

/**
 * The textual forms a workspace root takes in server output. [uris] and [paths] are replaced by
 * `{{workspace.uri}}` and `{{workspace.path}}`, longest form first, so snapshots hold no machine-specific text.
 */
internal data class WorkspaceForms(val uris: List<String>, val paths: List<String>) {
    companion object {
        /** Forms of [root], including its real path (macOS `/var` is `/private/var`). */
        fun of(root: Path): WorkspaceForms {
            val roots = listOfNotNull(root, runCatching { root.toRealPath() }.getOrNull())
            return from(roots.map { it.toString() to it.toUri().toString() })
        }

        /** Forms of workspace roots given as `(path, uri)` strings; also derives Windows spellings. */
        fun from(roots: List<Pair<String, String>>): WorkspaceForms {
            val uris = linkedSetOf<String>()
            val paths = linkedSetOf<String>()
            for ((path, uri) in roots) {
                val trimmedPath = path.trimEnd('/', '\\')
                paths += trimmedPath
                paths += trimmedPath.replace('\\', '/')
                paths += trimmedPath.replace('/', '\\')

                val trimmedUri = uri.trimEnd('/')
                val authorityless = trimmedUri.replaceFirst("file:///", "file:/")
                for (form in listOf(trimmedUri, authorityless)) {
                    uris += form
                    uris += form.replace(DRIVE_REGEX) { "${it.groupValues[1]}${it.groupValues[2].lowercase()}:" }
                    uris += form.replace(DRIVE_REGEX) { "${it.groupValues[1]}${it.groupValues[2].lowercase()}%3A" }
                    uris += form.replace(DRIVE_REGEX) { "${it.groupValues[1]}${it.groupValues[2]}%3A" }
                }
            }
            uris.removeIf { it.isEmpty() || it == "file:/" }
            paths.removeIf { it.isEmpty() }
            return WorkspaceForms(uris.sortedByDescending { it.length }, paths.sortedByDescending { it.length })
        }

        private val DRIVE_REGEX = "(file:/+)([A-Za-z]):".toRegex()
    }
}

/**
 * Snapshot expectations: renders a JSON value as stable text, compares it with a checked-in file, and in update mode
 * rewrites that file. Pure rendering and comparison live here; file IO is limited to [verify].
 */
internal object Snapshots {
    const val UPDATE_PROPERTY = "groovy.lsp.e2e.updateSnapshots"
    const val DIR_PROPERTY = "groovy.lsp.e2e.snapshotDir"
    const val WORKSPACE_URI = "{{workspace.uri}}"
    const val WORKSPACE_PATH = "{{workspace.path}}"

    /** Identity hash codes such as `Foo@1a2b3c4d` change per run, so a snapshot holding one can never be stable. */
    private val IDENTITY_HASH = "[A-Za-z_$][\\w$]*@[0-9a-f]{6,8}\\b".toRegex()

    private val prettyJson = Json { prettyPrint = true }

    /** Replaces every spelling of the workspace root in [text]. */
    fun normalize(text: String, forms: WorkspaceForms): String {
        var result = text
        for (uri in forms.uris) {
            // The placeholder stands for the root URI with its trailing slash, like `{{workspace.uri}}` in scenarios.
            result = result.replace(Regex.escape("$uri/").toRegex(), Regex.escapeReplacement(WORKSPACE_URI))
            result = result.replace(
                (Regex.escape(uri) + "(?![A-Za-z0-9_.\\-/])").toRegex(),
                Regex.escapeReplacement(WORKSPACE_URI),
            )
        }
        for (path in forms.paths) {
            result = result.replace(boundaryRegex(path), Regex.escapeReplacement(WORKSPACE_PATH))
        }
        return result
    }

    private fun boundaryRegex(form: String) = (Regex.escape(form) + "(?![A-Za-z0-9_.\\-])").toRegex()

    /** Renders [node]: a string as itself, anything else as pretty JSON with sorted keys; workspace text normalized. */
    fun render(node: JsonElement?, forms: WorkspaceForms): String {
        val text = when {
            node == null -> "null"
            node is JsonPrimitive && node.isString -> normalize(node.content, forms)
            else -> prettyJson.encodeToString(JsonElement.serializer(), normalizeTree(node, forms))
        }
        return text.replace("\r\n", "\n").let { if (it.endsWith("\n")) it else it + "\n" }
    }

    private fun normalizeTree(node: JsonElement, forms: WorkspaceForms): JsonElement = when (node) {
        is JsonObject -> JsonObject(
            node.entries
                .map { (key, value) -> normalize(key, forms) to normalizeTree(value, forms) }
                .sortedBy { it.first }
                .toMap(LinkedHashMap()),
        )

        is JsonArray -> JsonArray(node.map { normalizeTree(it, forms) })
        is JsonPrimitive -> if (node.isString) JsonPrimitive(normalize(node.content, forms)) else node
        JsonNull -> node
    }

    /** Throws if [rendered] holds an identity hash code; runs in update mode too so none is ever written. */
    fun requireStable(name: String, rendered: String) {
        val match = IDENTITY_HASH.find(rendered) ?: return
        throw AssertionError(
            "Snapshot '$name' contains an identity hash code (${match.value}) that changes on every run. " +
                "Make the server output deterministic, or assert a narrower jsonPath.",
        )
    }

    /**
     * Update mode is for local use only: CI must compare, never rewrite, and Bazel runs from a read-only runfiles tree.
     */
    fun requireUpdateAllowed(env: Map<String, String?>, props: (String) -> String?) {
        check(env["GITHUB_ACTIONS"].isNullOrEmpty()) {
            "Refusing to update snapshots when GITHUB_ACTIONS is set; update locally and commit the files."
        }
        check(env["TEST_TARGET"].isNullOrEmpty() && props("$DIR_PROPERTY.marker") == null) {
            "Refusing to update snapshots under Bazel; run the Gradle e2eTest task with -D$UPDATE_PROPERTY=true."
        }
    }

    /** Resolves [name] under [dir], rejecting absolute names and `..` escapes. */
    fun resolve(dir: Path, name: String): Path {
        require(name.isNotBlank()) { "snapshot name must not be blank" }
        val base = dir.toAbsolutePath().normalize()
        val file = base.resolve(name).normalize()
        require(!Path.of(name).isAbsolute && file.startsWith(base) && file != base) {
            "snapshot '$name' must be a relative path inside the snapshot directory"
        }
        return file
    }

    /** Line diff of [expected] against [actual]: `-` lines only in expected, `+` lines only in actual. */
    fun diff(expected: String, actual: String): String {
        val a = expected.removeSuffix("\n").lines()
        val b = actual.removeSuffix("\n").lines()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = StringBuilder()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> {
                    out.append("  ").append(a[i]).append('\n')
                    i++
                    j++
                }

                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                    out.append("- ").append(a[i++]).append('\n')
                }

                else -> out.append("+ ").append(b[j++]).append('\n')
            }
        }
        return out.toString()
    }

    /**
     * Compares [node] with the snapshot [name] under [dir]. With [update] the file is written instead (after the hash
     * guard). Throws [AssertionFailedError] carrying expected and actual text when they differ.
     */
    fun verify(dir: Path, name: String, node: JsonElement?, forms: WorkspaceForms, update: Boolean) {
        val file = resolve(dir, name)
        val actual = render(node, forms)
        requireStable(name, actual)
        if (update) {
            if (!Files.exists(file) || Files.readString(file).replace("\r\n", "\n") != actual) {
                Files.createDirectories(file.parent)
                Files.writeString(file, actual)
            }
            return
        }
        if (!Files.exists(file)) {
            throw AssertionFailedError(
                "Snapshot '$name' not found at $file. Create it with -D$UPDATE_PROPERTY=true (local runs only).",
                "",
                actual,
            )
        }
        val expected = Files.readString(file).replace("\r\n", "\n")
        if (expected != actual) {
            throw AssertionFailedError(
                "Snapshot '$name' differs ($file). Review the change, then run with -D$UPDATE_PROPERTY=true " +
                    "(local runs only) to accept it.\n${diff(expected, actual)}",
                expected,
                actual,
            )
        }
    }
}
