package com.github.albertocavalcante.groovyjenkins.plugins

/**
 * Configuration for Jenkins plugin discovery.
 *
 * This configuration allows the LSP client (e.g., VS Code) to customize
 * how plugins are discovered and which plugins are available.
 *
 * @param pluginsTxtPath Explicit path to plugins.txt, overrides auto-discovery
 * @param plugins Additional plugins from LSP client configuration
 * @param includeDefaultPlugins Whether to include built-in default plugins
 */
data class PluginConfiguration(
    val pluginsTxtPath: String? = null,
    val plugins: List<String> = emptyList(),
    val includeDefaultPlugins: Boolean = true,
) {
    companion object {
        /**
         * Default plugins that are almost always present in Jenkins installations.
         * These provide core functionality that most Jenkinsfiles depend on.
         *
         * NOTE: Version is null for defaults since actual version depends on installation.
         * plugins.txt or config can override with specific versions.
         */
        val DEFAULT_PLUGINS = listOf(
            "workflow-basic-steps", // echo, error, etc.
            "workflow-durable-task-step", // sh, bat, powershell
            "pipeline-model-definition", // declarative syntax
            "workflow-cps", // script block, CPS engine
        )

        /**
         * Parse PluginConfiguration from LSP settings map.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): PluginConfiguration {
            val pluginsTxtPath = map["jenkins.pluginsTxtPath"] as? String
            val plugins = (map["jenkins.plugins"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()
            val includeDefaults = (map["jenkins.includeDefaultPlugins"] as? Boolean) ?: true

            return PluginConfiguration(
                pluginsTxtPath = pluginsTxtPath,
                plugins = plugins,
                includeDefaultPlugins = includeDefaults,
            )
        }
    }
}
