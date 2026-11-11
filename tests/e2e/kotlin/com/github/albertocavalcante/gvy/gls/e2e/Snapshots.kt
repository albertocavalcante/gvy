package com.github.albertocavalcante.gvy.gls.e2e

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger("Snapshots")

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
                for (form in listOf(trimmedPath, trimmedPath.replace('\\', '/'), trimmedPath.replace('/', '\\'))) {
                    paths += form
                    if (WINDOWS_DRIVE.containsMatchIn(form)) paths += form.replaceFirstChar { it.lowercaseChar() }
                }

                val trimmedUri = uri.trimEnd('/')
                val authorityless = trimmedUri.replaceFirst("file:///", "file:/")
                for (form in listOf(trimmedUri, authorityless)) {
                    val variants = listOf(
                        form,
                        form.replace(DRIVE_REGEX) { "${it.groupValues[1]}${it.groupValues[2].lowercase()}:" },
                        form.replace(DRIVE_REGEX) { "${it.groupValues[1]}${it.groupValues[2].lowercase()}%3A" },
                        form.replace(DRIVE_REGEX) { "${it.groupValues[1]}${it.groupValues[2]}%3A" },
                    )
                    for (variant in variants) {
                        uris += variant
                        // Servers differ on percent-escape case (%3a vs %3A) and on whether a space is escaped.
                        uris += PERCENT_ESCAPE.replace(variant) { it.value.lowercase() }
                        uris += variant.replace("%20", " ")
                    }
                }
            }
            uris.removeIf { it.isEmpty() || it == "file:/" }
            paths.removeIf { it.isEmpty() }
            return WorkspaceForms(uris.sortedByDescending { it.length }, paths.sortedByDescending { it.length })
        }

        private val WINDOWS_DRIVE = "^[A-Za-z]:[\\\\/]".toRegex()
        private val PERCENT_ESCAPE = "%[0-9A-Fa-f]{2}".toRegex()
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
    private val IDENTITY_HASH = listOf(
        "[A-Za-z_$][\\w$]*@[0-9a-f]{6,8}\\b", // Foo@1a2b3c4d
        "[;\\]]@[0-9a-f]{1,8}\\b", // [Ljava.lang.String;@6d06d69c, int[]@1f
        "(?:[a-z_$][\\w$]*\\.)+[A-Z_$][\\w$]*@[0-9a-f]{1,8}\\b", // java.lang.Object@1f (short hash)
    ).joinToString("|").toRegex()

    private const val MAX_DIFF_LINES = 2000

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
        is JsonObject -> {
            val entries = node.entries.map { (key, value) -> normalize(key, forms) to normalizeTree(value, forms) }
            val collisions = entries.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.keys
            check(collisions.isEmpty()) {
                "Object keys collide after workspace normalization: $collisions. " +
                    "Assert a narrower jsonPath so each workspace file appears once."
            }
            JsonObject(entries.sortedBy { it.first }.toMap(LinkedHashMap()))
        }

        is JsonArray -> JsonArray(node.map { normalizeTree(it, forms) })
        is JsonPrimitive -> if (node.isString) JsonPrimitive(normalize(node.content, forms)) else node
        JsonNull -> node
    }

    /** An empty result usually means a filter or wildcard matched nothing; never record that as expected output. */
    private fun requireWorthWriting(name: String, node: JsonElement?) {
        check(node != null && node !is JsonNull && !(node is JsonArray && node.isEmpty())) {
            "Refusing to write snapshot '$name': the value is ${node ?: "null"}, so the jsonPath filter or wildcard " +
                "probably matched nothing. Fix the path or the server output first."
        }
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
        requireLocalArtifactUpdateAllowed(
            environment = env,
            kind = "snapshots",
            markerProperty = "$DIR_PROPERTY.marker",
            updateProperty = UPDATE_PROPERTY,
            properties = props,
        )
    }

    /** Resolves [name] under [dir], rejecting absolute names and `..` escapes. */
    fun resolve(dir: Path, name: String, forUpdate: Boolean = false): Path =
        resolveArtifactPath(dir, name, "snapshot", forUpdate)

    /** Line diff of [expected] against [actual]: `-` lines only in expected, `+` lines only in actual. */
    fun diff(expected: String, actual: String): String {
        val a = expected.removeSuffix("\n").lines()
        val b = actual.removeSuffix("\n").lines()
        if (a.size > MAX_DIFF_LINES || b.size > MAX_DIFF_LINES) {
            val first = a.zip(b).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(a.size, b.size) else it }
            val expectedLine = a.getOrElse(first) { "<end>" }
            val actualLine = b.getOrElse(first) { "<end>" }
            return "Too large to diff line by line (expected ${a.size} lines, actual ${b.size} lines). " +
                "First difference at line ${first + 1}:\n- $expectedLine\n+ $actualLine\n"
        }
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
        val file = resolve(dir, name, forUpdate = update)
        val actual = render(node, forms)
        requireStable(name, actual)
        if (update) {
            requireWorthWriting(name, node)
            if (!Files.exists(file) || Files.readString(file).replace("\r\n", "\n") != actual) {
                Files.createDirectories(file.parent)
                Files.writeString(file, actual)
                logger.warn { "Updated snapshot: $file" }
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
            val note = if (expected.trimEnd('\n') == actual.trimEnd('\n')) {
                "The texts differ only in trailing newlines.\n"
            } else {
                ""
            }
            throw AssertionFailedError(
                "Snapshot '$name' differs ($file). Review the change, then run with -D$UPDATE_PROPERTY=true " +
                    "(local runs only) to accept it.\n$note${diff(expected, actual)}",
                expected,
                actual,
            )
        }
    }
}
