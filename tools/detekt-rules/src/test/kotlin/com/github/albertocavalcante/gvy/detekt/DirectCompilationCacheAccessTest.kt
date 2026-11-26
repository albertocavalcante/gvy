// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.gitlab.arturbosch.detekt.test.yamlConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectCompilationCacheAccessTest {
    @Test
    fun `reports direct safe aliased imported and callable access`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.example
            import com.github.albertocavalcante.gvy.gls.compilation.getCompiledSnapshot as snapshot
            fun test(compilationService: Any, uri: Any) {
                compilationService.getCompiledSnapshot(uri)
                compilationService?.getAst(uri)
                val alias = compilationService
                alias.getTokenIndex(uri)
                snapshot(uri)
                val getter = compilationService::getParseResult
                compilationService.compiledTextOf(uri, uri, "")
                provider.provideHover(uri, position)
                compilationService.compile(uri, text)
            }
        """.trimIndent()
        assertThat(DirectCompilationCacheAccess().lint(source)).hasSize(8)
    }

    @Test
    fun `unrelated getters and code outside gls do not match`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.example
            fun test(context: RuleContext, diagnosticsService: DiagnosticsService) {
                context.getAst()
                diagnosticsService.getDiagnostics(uri, content)
                other.compile(uri, content)
            }
        """.trimIndent()
        assertThat(DirectCompilationCacheAccess().lint(source)).isEmpty()
        assertThat(DirectCompilationCacheAccess().lint("package unrelated\nfun f() = x.getCompiledSnapshot()"))
            .isEmpty()
    }

    @Test
    fun `registered default rule catches wire mutations`() {
        val config = yamlConfig("config/config.yml").subConfig("gvy")
        val rule = GvyRuleSetProvider().instance(config).rules.single { it.ruleId == "DirectCompilationCacheAccess" }
        assertThat((rule as DirectCompilationCacheAccess).active).isTrue()
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            fun handler(compilationService: Any) {
                compilationService.getCompiledSnapshot(uri)
                compilationService.compile(uri, text)
            }
        """.trimIndent()
        assertThat(rule.lint(source)).hasSize(2)
    }

    @Test
    fun `ordinary lint loads the active plugin configuration`() {
        val start = Path.of("").toAbsolutePath()
        val root = requireNotNull(generateSequence(start) { it.parent }.firstOrNull {
            Files.isRegularFile(it.resolve("settings.gradle.kts"))
        })
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        val build = Files.readString(root.resolve("build.gradle.kts"))
        val config = Files.readString(root.resolve("tools/lint/detekt.yml"))
        assertThat(settings).contains("include(\"tools:detekt-rules\")")
        assertThat(build).contains("detektPlugins(project(\":tools:detekt-rules\"))")
        assertThat(build).contains("config.setFrom(rootProject.files(\"tools/lint/detekt.yml\"))")
        assertThat(build).contains("dependsOn(\"detekt\", \"spotlessCheck\")")
        assertThat(build).contains("dependsOn(subprojects.map { it.tasks.named(\"lint\") })")
        assertThat(config).contains("  DirectCompilationCacheAccess:\n    active: true")
    }

    @Test
    fun `exact compiler forwarder exception rejects mutations`(@TempDir directory: Path) {
        val source = """
            package com.github.albertocavalcante.gvy.gls.compilation
            class GroovyCompilationService {
                fun getAst(uri: URI) = parseAccessor.getAst(uri)
            }
        """.trimIndent()
        val file = directory.resolve("GroovyCompilationService.kt")
        fun findings(code: String): Int {
            Files.writeString(file, code)
            return DirectCompilationCacheAccess().lint(file).size
        }
        assertThat(findings(source)).isZero()
        assertThat(findings(source.replace("fun getAst(uri: URI)", "fun other(uri: URI)"))).isEqualTo(1)
        assertThat(findings(source.replace("uri: URI", "uri: String"))).isEqualTo(1)
        assertThat(findings(source.replace("parseAccessor.getAst(uri)", "other.getAst(uri)"))).isEqualTo(1)
        assertThat(findings(source.replace("getAst(uri)", "getAst(other)"))).isEqualTo(1)
        val duplicate = source.replace(
            "= parseAccessor.getAst(uri)",
            "{ parseAccessor.getAst(uri); return parseAccessor.getAst(uri) }",
        )
        assertThat(findings(duplicate)).isEqualTo(1)
        val movedFile = directory.resolve("Other.kt")
        Files.writeString(movedFile, source)
        assertThat(DirectCompilationCacheAccess().lint(movedFile)).hasSize(1)
    }

    @Test
    fun `renamed typed receiver and shadowing never grant unrelated exemptions`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.example
            fun request(wire: GroovyCompilationService, uri: URI) {
                val alias = wire
                alias.compile(uri, text)
                wire.getAst(uri)
            }
            fun unrelated(context: RuleContext) = context.getAst()
            fun shadow(context: GroovyCompilationService) = context.getAst(uri)
        """.trimIndent()
        // Ambiguous same-name declarations are conservatively reported, even in the unrelated function.
        assertThat(DirectCompilationCacheAccess().lint(source)).hasSize(4)
    }

    private fun assertMarked(source: String) {
        val expected = source.lines().withIndex().filter { (_, line) -> "// FLAG" in line }.map { it.index + 1 }
        val actual = DirectCompilationCacheAccess().lint(source)
            .map { it.entity.location.source.line }
            .sorted()
        assertThat(expected).isNotEmpty()
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `unrelated import alias cannot rename compiler member selectors or references`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            import kotlin.collections.first as getCompiledSnapshot
            fun handler(compilationService: GroovyCompilationService, uri: URI) {
                compilationService.getCompiledSnapshot(uri) // FLAG
                compilationService?.getCompiledSnapshot(uri) // FLAG
                val read = compilationService::getCompiledSnapshot // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `real converter import alias remains guarded`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            import com.github.albertocavalcante.gvy.gls.converters.compiledTextOf as sourceText
            fun handler(
                compilationService: GroovyCompilationService, target: String, currentUri: URI, current: String,
            ) {
                compilationService.sourceText(target, currentUri, current) // FLAG
                val reader = compilationService::sourceText // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `compiler type import alias and derived receivers stay guarded`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService as Compiler
            suspend fun handler(wire: Compiler, optional: Compiler?, uri: URI, text: String) {
                wire.compile(uri, text) // FLAG
                optional?.compile(uri, text) // FLAG
                val alias = wire
                alias.compile(uri, text) // FLAG
                (wire).compile(uri, text) // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `unrelated parameter cannot exempt an inherited compiler receiver`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            fun diagnostic(context: RuleContext) = context.getAst()
            class Handler : Parent() {
                fun handle(uri: URI) = context.getAst(uri) // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `compiler extension receiver acquisitions are guarded`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            suspend fun GroovyCompilationService.handler(uri: URI, text: String) {
                compile(uri, text) // FLAG
                this.compile(uri, text) // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `additional real cache read APIs are guarded`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            suspend fun handler(compilationService: GroovyCompilationService, uri: URI, fingerprint: String) {
                compilationService.getCompiledContent(uri) // FLAG
                compilationService.getValidParseResult(uri) // FLAG
                compilationService.compilationCacheService.getCachedWithContent(uri, fingerprint) // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `real unrelated callable references and nullable getter do not report`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            import java.util.regex.Pattern
            fun inspect(context: RuleContext, nullable: RuleContext?, diagnostics: DiagnosticsService) {
                val readAst = context::getAst
                val readDiagnostics = diagnostics::getDiagnostics
                val regex = Pattern::compile
                nullable?.getAst()
            }
        """.trimIndent()
        assertThat(DirectCompilationCacheAccess().lint(source)).isEmpty()
    }

    @Test
    fun `destructured compiler receiver is not exempted by a different function`() {
        val source = """
            package com.github.albertocavalcante.gvy.gls.services
            fun diagnostic(context: RuleContext) = context.getAst()
            fun handler(wire: GroovyCompilationService, uri: URI) {
                val (context) = listOf(wire)
                context.getAst(uri) // FLAG
            }
        """.trimIndent()
        assertMarked(source)
    }

    @Test
    fun `same basename in another source path cannot reuse a compiler exception`(@TempDir directory: Path) {
        val file = directory.resolve("other/GroovyCompilationService.kt")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
                package com.github.albertocavalcante.gvy.gls.compilation
                class GroovyCompilationService {
                    fun getAst(uri: URI) = parseAccessor.getAst(uri)
                }
            """.trimIndent(),
        )
        assertThat(DirectCompilationCacheAccess().lint(file)).hasSize(1)
    }

    @Test
    fun `nested same-named class cannot reuse a compiler exception`(@TempDir directory: Path) {
        val file = directory.resolve("GroovyCompilationService.kt")
        Files.writeString(
            file,
            """
                package com.github.albertocavalcante.gvy.gls.compilation
                class Other {
                    class GroovyCompilationService {
                        fun getAst(uri: URI) = parseAccessor.getAst(uri)
                    }
                }
            """.trimIndent(),
        )
        assertThat(DirectCompilationCacheAccess().lint(file)).hasSize(1)
    }

    @Test
    fun `member extension cannot reuse a compiler exception`(@TempDir directory: Path) {
        val file = directory.resolve("GroovyCompilationService.kt")
        Files.writeString(
            file,
            """
                package com.github.albertocavalcante.gvy.gls.compilation
                class GroovyCompilationService {
                    fun Other.getAst(uri: URI) = parseAccessor.getAst(uri)
                }
            """.trimIndent(),
        )
        assertThat(DirectCompilationCacheAccess().lint(file)).hasSize(1)
    }

    @Test
    fun `ledger contains the exact six public forwarding shapes`() {
        assertThat(CACHE_ACCESS_EXCEPTIONS.map { it.key.function to it.key.expression }.toSet()).isEqualTo(
            setOf(
                "getAst" to "parseAccessor.getAst(uri)",
                "getParseResult" to "parseAccessor.getParseResult(uri)",
                "getDiagnostics" to "parseAccessor.getDiagnostics(uri)",
                "getTokenIndex" to "parseAccessor.getTokenIndex(uri)",
                "getAstModel" to "parseAccessor.getAstModel(uri)",
                "getSymbolTable" to "parseAccessor.getSymbolTable(uri)",
            ),
        )
    }

    @Test
    fun `ledger rejects changed call declaration and duplicate occurrence`() {
        val exception = CACHE_ACCESS_EXCEPTIONS.first()
        val key = exception.key
        assertThat(CACHE_ACCESS_EXCEPTIONS.allows(key, 1)).isTrue()
        assertThat(CACHE_ACCESS_EXCEPTIONS.allows(key, 2)).isFalse()
        assertThat(CACHE_ACCESS_EXCEPTIONS.allows(key.copy(expression = "parseAccessor.getAst(other)"), 1)).isFalse()
        assertThat(CACHE_ACCESS_EXCEPTIONS.allows(key.copy(function = "other"), 1)).isFalse()
        assertThat(CACHE_ACCESS_EXCEPTIONS.allows(key.copy(parameterTypes = listOf("String")), 1)).isFalse()
        assertThat(CACHE_ACCESS_EXCEPTIONS.allows(key.copy(file = "Other.kt"), 1)).isFalse()
    }

    @Test
    fun `ledger entries name exact compiler forwarding declarations`() {
        assertThat(CACHE_ACCESS_EXCEPTIONS).hasSize(6)
        assertThat(CACHE_ACCESS_EXCEPTIONS.map { it.key }.distinct()).hasSize(6)
        assertThat(CACHE_ACCESS_EXCEPTIONS).allSatisfy {
            assertThat(it.count).isEqualTo(1)
            assertThat(it.key.file).isEqualTo("GroovyCompilationService.kt")
            assertThat(it.key.parameterTypes).containsExactly("URI")
            assertThat(it.rationale).isNotBlank()
            assertThat(it.owner).isNotBlank()
            assertThat(it.removalGate).isNotBlank()
        }
    }
}
