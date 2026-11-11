package gvy.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.snakeyaml.engine.v2.exceptions.DuplicateKeyException
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DetektPathConfigTest {
    @TempDir
    lateinit var directory: Path

    private val defaults: String by lazy {
        checkNotNull(DetektPathConfigTest::class.java.getResourceAsStream("/default-detekt-config.yml"))
            .bufferedReader().use { it.readText() }
    }

    private val custom: String by lazy { File(System.getProperty("gvy.detekt.sourceConfig")).readText() }

    @Test
    fun `checkout ancestors never exclude production or either harness source root`() {
        val ancestors = listOf("neutral", "test", "src/test", "testFixtures", "dsl", "visitor", "androidTest")
        val rules = listOf(
            "style" to "MagicNumber",
            "complexity" to "TooManyFunctions",
            "exceptions" to "InstanceOfCheckForException",
            "potential-bugs" to "UnsafeCallOnNullableType",
        )
        for (ancestor in ancestors) {
            val root = directory.resolve(ancestor).resolve("repo")
            val policy = DetektPathConfig.overlay(defaults, custom, listOf(root.toString()))
            for ((group, rule) in rules) {
                val filters = filters(policy, group, rule)
                for (relative in listOf(
                    "gls/src/main/kotlin/Probe.kt",
                    "tests/e2e/kotlin/Probe.kt",
                    "tests/lsp-client/kotlin/Probe.kt",
                )) {
                    assertFalse(filters.ignored(root.resolve(relative)), "$ancestor: $group/$rule excludes $relative")
                }
                assertTrue(
                    filters.ignored(root.resolve("gls/src/test/kotlin/Probe.kt")),
                    "$group/$rule lost test policy",
                )
            }
        }
    }

    @Test
    fun `ruleset filters are anchored without rewriting metadata validation patterns`() {
        val root = directory.resolve("test/repo")
        val input = """
            config:
              excludes: 'plugin-rules,.*>.*>customProperty'
            style:
              includes: ['**/*.kt']
              excludes: ['**/test/**']
        """.trimIndent()
        val values = DetektPathConfig.read(DetektPathConfig.overlay(defaults, input, listOf(root.toString())))
        val group = mapping(values["style"])
        val filters = ActualPathFilters(strings(group["includes"]), strings(group["excludes"]))
        assertFalse(filters.ignored(root.resolve("src/main/Probe.kt")))
        assertFalse(filters.ignored(root.resolve("Root.kt")))
        assertTrue(filters.ignored(root.resolve("src/test/Probe.kt")))
        assertEquals(DetektPathConfig.read(input)["config"], values["config"])
    }

    @Test
    fun `direct and nested repository DSL and visitor directories retain their exemptions`() {
        val root = directory.resolve("repo")
        val policy = DetektPathConfig.overlay(defaults, custom, listOf(root.toString()))
        val filters = filters(policy, "complexity", "TooManyFunctions")
        for (relative in listOf(
            "dsl/Probe.kt",
            "visitor/Probe.kt",
            "semantics/dsl/Probe.kt",
            "parser/visitor/Probe.kt",
        )) {
            assertTrue(filters.ignored(root.resolve(relative)), "Lost exemption for $relative")
        }
    }

    @Test
    fun `literal root metacharacters cannot match a neighboring checkout`() {
        val rootName = if (File.separatorChar == '\\') "repo[1]{x}" else "repo[1]{x}?*\\end"
        val root = directory.resolve(rootName)
        val policy = DetektPathConfig.overlay(
            defaults,
            "style:\n  MagicNumber:\n    includes: ['**/*.kt']\n",
            listOf(root.toString()),
        )
        val filters = filters(policy, "style", "MagicNumber")
        assertFalse(filters.ignored(root.resolve("src/main/Probe.kt")))
        assertTrue(filters.ignored(directory.resolve("repo1x-anything-end/src/main/Probe.kt")))
        assertTrue(filters.ignored(root.resolve("src/test/Probe.kt")))
        assertFalse(filters.ignored(root.resolve("Root.kt")), "Leading **/ also matches a root-level file")
    }

    @Test
    fun `both lexical and physical root spellings carry the same policy`() {
        val physical = directory.resolve("physical")
        val lexical = directory.resolve("checkout")
        val policy = DetektPathConfig.overlay(defaults, custom, listOf(lexical.toString(), physical.toString()))
        val filters = filters(policy, "style", "MagicNumber")
        for (root in listOf(lexical, physical)) {
            assertFalse(filters.ignored(root.resolve("src/main/Probe.kt")))
            assertTrue(filters.ignored(root.resolve("src/test/Probe.kt")))
        }
    }

    @Test
    fun `empty lists override inherited filters and null retains inherited filters`() {
        val root = directory.resolve("repo")
        val empty = DetektPathConfig.overlay(
            defaults,
            "style:\n  MagicNumber:\n    excludes: []\n",
            listOf(root.toString()),
        )
        assertFalse(filters(empty, "style", "MagicNumber").ignored(root.resolve("src/test/Probe.kt")))
        val nullable = DetektPathConfig.overlay(
            defaults,
            "style:\n  MagicNumber:\n    excludes: null\n",
            listOf(root.toString()),
        )
        assertTrue(filters(nullable, "style", "MagicNumber").ignored(root.resolve("src/test/Probe.kt")))
    }

    @Test
    fun `non-path policy remains unchanged including configuration-validation exclusions`() {
        val input = """
            config:
              excludes: 'custom-rule-set,.*>.*>customProperty'
            style:
              MagicNumber:
                active: false
                ignoreNumbers: ['7']
                excludes: ['**/special/**']
        """.trimIndent()
        val result = DetektPathConfig.read(DetektPathConfig.overlay(defaults, input, listOf(directory.toString())))
        assertEquals(DetektPathConfig.read(input)["config"], result["config"])
        val rule = mapping(mapping(result["style"])["MagicNumber"])
        assertEquals(false, rule["active"])
        assertEquals(listOf("7"), rule["ignoreNumbers"])
        assertTrue(
            filters(DetektPathConfig.dump(result), "style", "MagicNumber").ignored(directory.resolve("special/A.kt")),
        )
    }

    @Test
    fun `plugin defaults are inherited and duplicate default keys fail visibly`() {
        val plugin = """
            plugin-rules:
              InheritedRule:
                excludes: ['**/test/**']
        """.trimIndent()
        val coreJar = jar("core.jar", "default-detekt-config.yml", defaults)
        val pluginJar = jar("plugin.jar", "config/config.yml", plugin)
        val combinedDefaults = DetektPathConfig.defaults(listOf(coreJar, pluginJar))
        val root = directory.resolve("test/repo")
        val policy = DetektPathConfig.overlay(combinedDefaults, custom, listOf(root.toString()))
        assertFalse(filters(policy, "plugin-rules", "InheritedRule").ignored(root.resolve("main/A.kt")))
        assertTrue(filters(policy, "plugin-rules", "InheritedRule").ignored(root.resolve("src/test/A.kt")))
        val duplicateJar = jar("duplicate.jar", "config/config.yml", "style:\n  active: true\n")
        assertThrows(DuplicateKeyException::class.java) {
            DetektPathConfig.overlay(
                DetektPathConfig.defaults(listOf(coreJar, duplicateJar)),
                custom,
                listOf(root.toString()),
            )
        }
    }

    @Test
    fun `default resource loading is isolated and requires exactly one core configuration`() {
        // The test runtime already contains Detekt defaults. They must not leak through the parent loader.
        assertThrows(IllegalArgumentException::class.java) { DetektPathConfig.defaults(emptyList()) }
        val first = jar("first.jar", "default-detekt-config.yml", defaults)
        val second = jar("second.jar", "default-detekt-config.yml", defaults)
        assertThrows(IllegalArgumentException::class.java) { DetektPathConfig.defaults(listOf(first, second)) }
        assertEquals(defaults, DetektPathConfig.defaults(listOf(first)))
    }

    @Test
    fun `output is deterministic and changes when the repository root changes`() {
        val first = DetektPathConfig.overlay(defaults, custom, listOf(directory.resolve("one").toString()))
        assertEquals(first, DetektPathConfig.overlay(defaults, custom, listOf(directory.resolve("one").toString())))
        val second = DetektPathConfig.overlay(defaults, custom, listOf(directory.resolve("two").toString()))
        assertFalse(first == second)
    }

    @Test
    fun `malformed rulesets and known rules stay rejected instead of becoming default policy`() {
        for (value in listOf("false", "'not-a-mapping'", "[]")) {
            for (input in listOf("style: $value\n", "style:\n  MagicNumber: $value\n")) {
                assertThrows(IllegalArgumentException::class.java) {
                    DetektPathConfig.overlay(defaults, input, listOf(directory.toString()))
                }
            }
        }
        val input = "formatting:\n  active: true\n  android: false\nstyle:\n  active: false\n"
        val generated = DetektPathConfig.read(DetektPathConfig.overlay(defaults, input, listOf(directory.toString())))
        assertEquals(true, mapping(generated["formatting"])["active"])
        assertEquals(false, mapping(generated["formatting"])["android"])
        assertEquals(false, mapping(generated["style"])["active"])
    }

    @Test
    fun `invalid filter values fail clearly`() {
        for (value in listOf("123", "[true]")) {
            assertThrows(IllegalArgumentException::class.java) {
                DetektPathConfig.overlay(
                    defaults,
                    "style:\n  MagicNumber:\n    excludes: $value\n",
                    listOf(directory.toString()),
                )
            }
        }
    }

    @Test
    fun `explicit absolute globs retain their intentional scope`() {
        val excluded = directory.resolve("shared").toString().replace(File.separatorChar, '/') + "/**"
        val input = "style:\n  MagicNumber:\n    excludes: ['$excluded']\n"
        val policy = DetektPathConfig.overlay(defaults, input, listOf(directory.resolve("repo").toString()))
        val rule = mapping(mapping(DetektPathConfig.read(policy)["style"])["MagicNumber"])
        assertEquals(listOf(excluded), rule["excludes"])
        assertTrue(filters(policy, "style", "MagicNumber").ignored(directory.resolve("shared/A.kt")))
        assertFalse(filters(policy, "style", "MagicNumber").ignored(directory.resolve("repo/src/test/A.kt")))
    }

    private fun filters(yaml: String, group: String, rule: String): ActualPathFilters {
        val values = mapping(mapping(DetektPathConfig.read(yaml)[group])[rule])
        return ActualPathFilters(strings(values["includes"]), strings(values["excludes"]))
    }

    private fun mapping(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<String, Any?>()

    private fun jar(name: String, entry: String, content: String): File =
        directory.resolve(name).toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }

    private fun strings(value: Any?): List<String> = (value as? List<*>)?.map { checkNotNull(it as? String) }.orEmpty()

    // Detekt exposes this JVM API, but marks it Kotlin-internal. Exercise its real matcher, not a duplicate.
    private class ActualPathFilters(includes: List<String>, excludes: List<String>) {
        private val type = Class.forName("io.gitlab.arturbosch.detekt.api.internal.PathFilters")
        private val companion = type.getField("Companion").get(null)
        private val filters = companion.javaClass.getMethod("of", List::class.java, List::class.java)
            .invoke(companion, includes, excludes)

        fun ignored(path: Path): Boolean = filters != null && type.getMethod("isIgnored", Path::class.java)
            .invoke(filters, path) == true
    }
}
