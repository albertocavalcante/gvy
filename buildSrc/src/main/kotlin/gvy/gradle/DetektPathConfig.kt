package gvy.gradle

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/** Makes rule path filters repository-relative without changing the remaining custom or default policy. */
object DetektPathConfig {
    private val pathProperties = listOf("includes", "excludes")
    private val metadataGroups = setOf("build", "config", "processors", "console-reports", "output-reports")

    fun overlay(defaultYaml: String, customYaml: String, repositoryRoots: List<String>): String {
        require(repositoryRoots.isNotEmpty()) { "A repository root is required" }
        val roots = repositoryRoots.distinct().map(::literalRoot)
        val defaults = read(defaultYaml)
        val custom = read(customYaml)
        val result = custom.toMutableMap()
        for (group in defaults.keys + custom.keys) {
            if (group in metadataGroups) continue
            val defaultGroup = optionalMapping(defaults[group])
            val customGroup = optionalMapping(custom[group])
            val outputGroup = anchoredFilters(defaultGroup, customGroup, roots, group).toMutableMap()
            for (rule in defaultGroup.keys + customGroup.keys) {
                if (defaultGroup[rule] !is Map<*, *> && customGroup[rule] !is Map<*, *>) continue
                val defaultRule = optionalMapping(defaultGroup[rule])
                val customRule = optionalMapping(customGroup[rule])
                val outputRule = anchoredFilters(defaultRule, customRule, roots, "$group.$rule")
                if (outputRule != customRule) outputGroup[rule] = outputRule
            }
            if (outputGroup != customGroup) result[group] = outputGroup
        }
        return "# Generated repository-specific Detekt path filters; edit tools/lint/detekt.yml.\n" + dump(result)
    }

    private fun anchoredFilters(
        defaults: Map<String, Any?>,
        custom: Map<String, Any?>,
        roots: List<String>,
        path: String,
    ): Map<String, Any?> = custom.toMutableMap().apply {
        for (property in pathProperties) {
            // CompositeConfig falls back only for null/missing leaves. An empty custom list overrides defaults.
            val value = custom[property] ?: defaults[property] ?: continue
            val patterns = stringList(value, "$path.$property")
            this[property] = patterns.flatMap { pattern -> anchor(pattern, roots) }.distinct()
        }
    }

    /** Match Detekt's default resource order and let the YAML loader reject duplicate plugin keys. */
    fun defaults(classpath: Collection<File>): String =
        URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), null).use { loader ->
            val core = loader.getResources("default-detekt-config.yml").toList()
            require(core.size == 1) { "Expected exactly one Detekt default config, found ${core.size}" }
            buildString {
                append(resourceText(core.single()))
                for (plugin in loader.getResources("config/config.yml").toList()) {
                    appendLine()
                    append(resourceText(plugin))
                }
            }
        }

    private fun resourceText(resource: URL): String = resource.openConnection().apply {
        // Each producer owns its streams. A shared JarURLConnection cache can close another producer's JAR.
        useCaches = false
    }.getInputStream().bufferedReader().use { it.readText() }

    fun read(yaml: String): Map<String, Any?> {
        val value = Load(LoadSettings.builder().setAllowDuplicateKeys(false).build()).loadFromString(yaml)
        return if (value == null) emptyMap() else mapping(value)
    }

    fun dump(value: Map<String, Any?>): String = Dump(
        DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).build(),
    ).dumpToString(value)

    private fun optionalMapping(value: Any?): Map<String, Any?> = if (value == null) emptyMap() else mapping(value)

    private fun mapping(value: Any): Map<String, Any?> {
        require(value is Map<*, *>) { "Expected a YAML mapping" }
        return value.entries.associateTo(linkedMapOf()) { (key, entry) ->
            require(key is String) { "Detekt configuration keys must be strings" }
            key to entry
        }
    }

    private fun stringList(value: Any, property: String): List<String> {
        require(value is List<*>) { "$property must be a list of path globs" }
        return value.map { pattern ->
            require(pattern is String) { "$property must contain only string path globs" }
            pattern
        }
    }

    private fun anchor(pattern: String, roots: List<String>): List<String> {
        // Explicit absolute globs retain their deliberate scope; only relative policy is checkout-independent.
        if (pattern.startsWith('/') || pattern.startsWith("\\\\") ||
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(pattern)
        ) {
            return listOf(pattern)
        }
        // Java glob's **/ requires a separator. Include its zero-directory forms for root-level files/directories.
        val forms = generateSequence(pattern) { current ->
            current.takeIf { it.startsWith("**/") }?.removePrefix("**/")
        }.toList()
        return roots.flatMap { root -> forms.map { form -> "$root/$form" } }
    }

    private fun literalRoot(root: String): String {
        require(File(root).isAbsolute) { "Detekt repository roots must be absolute" }
        val normalized = if (File.separatorChar == '\\') root.replace('\\', '/') else root
        return buildString {
            for (character in normalized.trimEnd('/')) {
                if (character in "\\*?[]{}") append('\\')
                append(character)
            }
        }
    }
}
