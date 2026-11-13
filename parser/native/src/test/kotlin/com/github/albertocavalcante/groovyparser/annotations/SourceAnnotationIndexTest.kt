// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.annotations

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.AnnotationCoverage
import com.github.albertocavalcante.nativeapi.AnnotationImportBinding
import com.github.albertocavalcante.nativeapi.AnnotationReferenceKind
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserPosition
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAnnotationIndexTest {
    private val results = mutableListOf<ParseResult>()

    private fun parse(source: String, phase: Int = Phases.CANONICALIZATION): ParseResult = parse(
        ParseRequest(URI.create("file:///Money.groovy"), source, compilePhase = phase),
    )

    private fun parse(request: ParseRequest): ParseResult =
        GroovyParserFacade(groovy.transform.CompileStatic::class.java.classLoader).parse(request).also(results::add)

    @AfterEach
    fun closeCompilerClassLoaders() {
        results.forEach { it.compilationUnit.classLoader.close() }
        results.clear()
    }

    @Test
    fun `a selected source annotation records its compiler source unit without retaining its AST`() {
        val result = parse("@interface Mark {}\n@Mark class Money {}")
        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val reference = result.sourceAnnotations.references.single { it.spelling == "Mark" }

        assertEquals("Mark", reference.binaryName)
        assertEquals("/Money.groovy", reference.sourceUnitName)
        assertEquals(URI.create("file:///Money.groovy"), reference.sourceUri)
    }

    @Test
    fun `a missing annotation stub is not a proven source or binary binding`() {
        val result = parse("import missing.Mark\n@Mark class Money {}")
        assertTrue(result.outcome.isDegraded)
        val reference = result.sourceAnnotations.references.single { it.spelling == "Mark" }

        assertEquals(null, reference.binaryName)
        assertEquals(null, reference.sourceUnitName)
    }

    @Test
    fun `selected source URIs preserve file authorities Windows paths and virtual document identities`() {
        val uris = listOf(
            URI.create("file://server/share/Money.groovy"),
            URI.create("file:///C:/workspace/Money.groovy"),
            URI.create("untitled:Money.groovy"),
        )
        for (uri in uris) {
            val result = parse(
                ParseRequest(uri, "@interface Mark {}\n@Mark class Money {}", compilePhase = Phases.CANONICALIZATION),
            )
            assertTrue(result.isSuccessful, result.diagnostics.toString())
            assertEquals(uri, result.sourceAnnotations.references.single { it.spelling == "Mark" }.sourceUri)
        }
    }

    @Test
    fun `conversion syntax alone does not invent a selected source annotation URI`() {
        val result = parse("@interface Mark {}\n@Mark class Money {}", phase = Phases.CONVERSION)
        assertEquals(null, result.sourceAnnotations.references.single { it.spelling == "Mark" }.sourceUri)
    }

    @Test
    fun `only selected foreign source URIs and immutable declaration headers survive the capture builder`() {
        val selected = URI.create("file:///selected/Mark.groovy")
        val unrelated = URI.create("file:///unrelated/Other.groovy")
        val result = parse(
            ParseRequest(
                URI.create("file:///Money.groovy"),
                "import selected.Mark\n@Mark class Money {}",
                compilePhase = Phases.CANONICALIZATION,
                openSources = mapOf(
                    selected to "package selected\n@interface Mark {}",
                    unrelated to "package unrelated\n@interface Other {}",
                ),
            ),
        )
        assertTrue(result.isSuccessful, result.diagnostics.toString())
        assertEquals(selected, result.sourceAnnotations.sourceUri(selected.path))
        assertNotNull(result.sourceAnnotations.foreignDeclaration(selected.path, "selected.Mark"))
        assertNull(result.sourceAnnotations.sourceUri(unrelated.path))
        assertNull(result.sourceAnnotations.foreignDeclaration(unrelated.path, "unrelated.Other"))
    }

    @Test
    fun `oversized annotation names preserve uncertainty before candidate prefix expansion`() {
        val name = "Long" + "A".repeat(4096)
        val result = parse("import java.util.List\n@$name class Money {}", phase = Phases.CONVERSION)
        assertTrue(result.isSuccessful)
        assertTrue(result.sourceAnnotations.coverage is AnnotationCoverage.Partial)
        assertTrue(result.sourceAnnotations.references.isEmpty())
        assertTrue(result.ast?.imports.orEmpty().none(result.sourceAnnotations::canRuleOutAnnotationUse))
    }

    @Test
    fun `source annotation reference storage is capped without certifying missing import uses`() {
        val source = "import java.util.List\n@interface Mark {}\n" + "@Mark\n".repeat(16_385) + "class Money {}"
        val result = parse(source, phase = Phases.CONVERSION)
        assertTrue(result.isSuccessful)
        assertTrue(result.sourceAnnotations.coverage is AnnotationCoverage.Partial)
        assertTrue(result.sourceAnnotations.references.size <= 16_384)
        assertTrue(result.ast?.imports.orEmpty().none(result.sourceAnnotations::canRuleOutAnnotationUse))
    }

    @Test
    fun `collector source identity is retained after its annotation is expanded`() {
        val result = parse("import groovy.transform.Immutable as Value\n@Value\nclass Money { BigDecimal amount }")

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val index = result.sourceAnnotations
        assertEquals(AnnotationCoverage.Complete, index.coverage)
        val reference = index.references.single { it.kind == AnnotationReferenceKind.HEAD }
        assertEquals("Value", reference.spelling)
        assertEquals("groovy.transform.Immutable", reference.binaryName)
        assertEquals(ParserRange(ParserPosition(1, 1), ParserPosition(1, 6)), reference.nameRange)
        assertIs<AnnotationImportBinding.Explicit>(reference.importBinding)
        assertFalse(
            assertNotNull(result.ast).classes.single().annotations.any { it.classNode.name.endsWith(".Immutable") },
        )
        assertNotNull(index.headAt(1, 1))
        assertNotNull(index.headAt(1, 5))
        assertNull(index.headAt(1, 0), "The at sign is not part of the annotation name")
        assertNull(index.headAt(1, 6), "The range is half open")
    }

    @Test
    fun `fully qualified and alternate alias uses have exact import attribution`() {
        val qualified = parse("import groovy.transform.CompileStatic\n@groovy.transform.CompileStatic\nclass Money {}")
        assertTrue(qualified.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(qualified.ast).imports.single()))
        assertEquals(AnnotationImportBinding.None, qualified.sourceAnnotations.references.single().importBinding)

        val aliased = parse(
            "import groovy.transform.Immutable as Value\nimport groovy.transform.Immutable as Other\n" +
                "@Value\nclass Money { BigDecimal amount }",
        )
        val imports = assertNotNull(aliased.ast).imports.associateBy { it.alias }
        assertFalse(aliased.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(imports["Value"])))
        assertTrue(aliased.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(imports["Other"])))
    }

    @Test
    fun `ordinary annotation imports keep precedence over colliding static aliases`() {
        val result = parse(
            "import groovy.transform.CompileStatic as Mark\n" +
                "import static java.lang.Math.PI as Mark\n" +
                "@Mark class Money {}",
        )
        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val module = assertNotNull(result.ast)
        val typeImport = module.imports.single()
        val staticImport = module.staticImports.getValue("Mark")
        val reference = result.sourceAnnotations.references.single { it.kind == AnnotationReferenceKind.HEAD }

        assertEquals("groovy.transform.CompileStatic", reference.binaryName)
        assertEquals(
            AnnotationImportBinding.Explicit(assertNotNull(result.sourceAnnotations.importRange(typeImport))),
            reference.importBinding,
        )
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(typeImport))
        assertTrue(result.sourceAnnotations.canRuleOutAnnotationUse(staticImport))
    }

    @Test
    fun `annotation argument collisions retain both ordinary and static import candidates`() {
        val result = parse(
            "import java.lang.String as Mark\n" +
                "import static java.lang.Integer.MAX_VALUE as Mark\n" +
                "@interface Marker { int value() }\n" +
                "@Marker(Mark) class Money {}",
            phase = Phases.CONVERSION,
        )
        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val argument = result.sourceAnnotations.references.single { it.kind == AnnotationReferenceKind.ARGUMENT }
        val binding = assertIs<AnnotationImportBinding.Unknown>(argument.importBinding)
        val module = assertNotNull(result.ast)

        assertEquals(2, binding.candidates.size)
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(module.imports.single()))
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(module.staticImports.getValue("Mark")))
    }

    @Test
    fun `type-use annotations are captured from generic type occurrences`() {
        val result = parse(
            """
            package example
            import java.lang.annotation.Target
            import java.lang.annotation.ElementType
            @Target([ElementType.TYPE_USE]) @interface Mark {}
            class Money { List<@Mark String> names }
            """.trimIndent(),
        )

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val mark = result.sourceAnnotations.references.single { it.spelling == "Mark" }
        assertEquals("example.Mark", mark.binaryName)
        assertEquals(ParserRange.singleLine(4, 20, 24), mark.nameRange)
    }

    @Test
    fun `inlined annotation arguments preserve candidate imports without claiming a semantic target`() {
        val result = parse(
            """
            import java.lang.annotation.Target
            import static java.lang.annotation.ElementType.TYPE as KIND
            import java.util.HashMap
            @Target(KIND) @interface Mark {}
            """.trimIndent(),
        )

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val index = result.sourceAnnotations
        val argument = index.references.single { it.kind == AnnotationReferenceKind.ARGUMENT }
        assertEquals("KIND", argument.spelling)
        assertNull(argument.binaryName)
        assertIs<AnnotationImportBinding.Unknown>(argument.importBinding)
        val module = assertNotNull(result.ast)
        assertFalse(index.canRuleOutAnnotationUse(module.staticImports.getValue("KIND")))
        assertTrue(index.canRuleOutAnnotationUse(module.imports.single { it.className == "java.util.HashMap" }))
    }

    @Test
    fun `source ranges convert supplementary characters to UTF16 without shifting the annotation name`() {
        val source = "import groovy.transform.ToString\r\n/*😀*/\t@ToString(includeNames=true) class Money {}"
        val result = parse(source)
        val reference = result.sourceAnnotations.references.single()

        assertEquals(ParserRange.singleLine(1, 8, 16), reference.nameRange)
        assertNotNull(result.sourceAnnotations.headAt(1, 8))
        assertNull(result.sourceAnnotations.headAt(1, 7))
        assertNull(result.sourceAnnotations.headAt(1, 16))
    }

    @Test
    fun `stripped execution annotations are preserved as source without running their closures`() {
        val result = parse(
            "import groovy.transform.ASTTest\n@ASTTest(value={ throw new AssertionError('executed') })\nclass Money {}",
        )

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        assertTrue(result.sourceAnnotations.references.any { it.spelling == "ASTTest" })
        assertIs<AnnotationCoverage.Partial>(result.sourceAnnotations.coverage)
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(result.ast).imports.single()))
    }

    @Test
    fun `comments and strings are not annotation occurrences and recursive generic bounds terminate`() {
        val result = parse(
            """
            import groovy.transform.ToString
            // @ToString
            class Money<T extends Comparable<T>> { String text = '@ToString' }
            """.trimIndent(),
        )

        assertEquals(emptyList(), result.sourceAnnotations.references)
        assertEquals(AnnotationCoverage.Complete, result.sourceAnnotations.coverage)
        assertTrue(result.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(result.ast).imports.single()))
    }

    @Test
    fun `conversion-only evidence preserves unresolved imported annotation names conservatively`() {
        val result = parse("import missing.Annotation\n@Annotation\nclass Money {}", Phases.CONVERSION)

        assertEquals(AnnotationCoverage.Complete, result.sourceAnnotations.coverage)
        assertIs<AnnotationImportBinding.Unknown>(result.sourceAnnotations.references.single().importBinding)
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(result.ast).imports.single()))
    }

    @Test
    fun `field and property projections of one written annotation are deduplicated`() {
        val result = parse(
            "import java.lang.Deprecated\nclass Money { @Deprecated String name }",
        )

        assertEquals(listOf("Deprecated"), result.sourceAnnotations.references.map { it.spelling })
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(assertNotNull(result.ast).imports.single()))
    }

    @Test
    fun `a failed conversion cannot establish annotation non-use`() {
        val result = parse("import groovy.transform.Immutable\n@Immutable(\nclass Money {")

        assertFalse(result.isSuccessful)
        assertTrue(result.sourceAnnotations.coverage != AnnotationCoverage.Complete)
        result.ast?.imports?.forEach { assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(it)) }
    }

    @Test
    fun `annotation declaration name is captured after meta annotations before collector rewriting`() {
        val result = parse(
            """
            import groovy.transform.AnnotationCollector
            import groovy.transform.CompileStatic
            @AnnotationCollector([CompileStatic])
            @interface Model {}
            @Model class Money {}
            """.trimIndent(),
        )

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val declaration = result.sourceAnnotations.declarations.single()
        assertEquals("Model", declaration.binaryName)
        assertEquals(ParserRange.singleLine(3, 11, 16), declaration.nameRange)
        assertEquals(2, declaration.declarationRange.start.line)
    }

    @Test
    fun `type-use occurrences in signatures loops array construction and generic calls are not dropped`() {
        val result = parse(
            """
            import java.lang.annotation.Target
            import java.lang.annotation.ElementType
            @Target([ElementType.TYPE_USE]) @interface Mark {}
            class Money {
                List<@Mark String> names
                List<@Mark String> copy(List<@Mark String> source) {
                    List<@Mark String> local = source
                    for (List<@Mark String> group in []) {}
                    new @Mark String[1]
                    Collections.<@Mark String>emptyList()
                    local
                }
            }
            """.trimIndent(),
        )

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val occurrences = result.sourceAnnotations.references.filter { it.spelling == "Mark" }
        assertEquals(listOf(4, 5, 5, 6, 7, 8, 9), occurrences.map { it.nameRange.start.line }.sorted())
    }

    @Test
    fun `annotation method defaults protect statically imported constants before inlining`() {
        val result = parse(
            """
            import java.lang.annotation.ElementType
            import static java.lang.annotation.ElementType.TYPE
            import java.util.HashMap
            @interface Mark { ElementType value() default TYPE }
            """.trimIndent(),
        )

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        val module = assertNotNull(result.ast)
        assertFalse(result.sourceAnnotations.canRuleOutAnnotationUse(module.staticImports.getValue("TYPE")))
        assertTrue(
            result.sourceAnnotations.canRuleOutAnnotationUse(
                module.imports.single { it.className == "java.util.HashMap" },
            ),
        )
    }

    @Test
    fun `Groovy LF-only coordinates map bare CR source lines into editor positions`() {
        val result = parse("import groovy.transform.CompileStatic\r@CompileStatic\rclass Money {}")

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        assertEquals(ParserRange.singleLine(1, 1, 14), result.sourceAnnotations.references.single().nameRange)
    }

    @Test
    fun `comments between qualified annotation segments are not name occurrences`() {
        val source = "@groovy /*comment*/ . transform . CompileStatic class Money {}"
        val result = parse(source)
        val index = result.sourceAnnotations

        assertTrue(result.isSuccessful, result.diagnostics.toString())
        assertNotNull(index.headAt(0, source.indexOf("groovy")))
        assertNotNull(index.headAt(0, source.indexOf("CompileStatic")))
        assertNull(index.headAt(0, source.indexOf("comment")))
        assertTrue(index.isHeadNameTriviaAt(0, source.indexOf("comment")))
    }
}
