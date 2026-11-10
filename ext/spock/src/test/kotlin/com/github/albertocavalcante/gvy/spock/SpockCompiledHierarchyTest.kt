package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CompileUnit
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bases declared in other files of the same compile, as the workspace compile delivers them. */
class SpockCompiledHierarchyTest {
    private fun verdict(
        source: String,
        others: Map<String, String> = emptyMap(),
        name: String,
        loader: ClassLoader = SpockCompiledHierarchyTest::class.java.classLoader,
    ): SpockClassContext {
        val request = ParseRequest(
            uri = URI.create("file:///src/Probe.groovy"),
            content = source,
            openSources = others.mapKeys { (file, _) -> URI.create("file:///src/$file") },
            compilePhase = Phases.SEMANTIC_ANALYSIS,
        )
        val result = GroovyParserFacade(loader).parse(request)
        val context = assertIs<SpockSourceContext.Available>(SpockSourceContext.from(result))
        return context.classes.single { it.classNode.name == name }
    }

    private val baseSpec = mapOf(
        "BaseSpec.groovy" to "import spock.lang.Specification\nclass BaseSpec extends Specification {}",
    )

    @Test
    fun `a base spec in another file of the same compile is a specification`() {
        val foo = verdict("class FooSpec extends BaseSpec {}", baseSpec, "FooSpec")
        assertEquals(SpecificationStatus.SPECIFICATION, foo.status)
        assertEquals("COMPILED_SOURCE_HIERARCHY", foo.evidence.name)
    }

    @Test
    fun `a multi-file chain to Specification is a specification`() {
        val others = baseSpec + ("MidSpec.groovy" to "class MidSpec extends BaseSpec {}")
        val foo = verdict("class FooSpec extends MidSpec {}", others, "FooSpec")
        assertEquals(SpecificationStatus.SPECIFICATION, foo.status)
        assertEquals("COMPILED_SOURCE_HIERARCHY", foo.evidence.name)
    }

    @Test
    fun `an ordinary base in another file is not a specification`() {
        val rule = verdict("class Rule extends Base {}", mapOf("Base.groovy" to "class Base {}"), "Rule")
        assertEquals(SpecificationStatus.NOT_SPECIFICATION, rule.status)
        assertEquals("COMPILED_SOURCE_HIERARCHY", rule.evidence.name)
    }

    @Test
    fun `a base that is not part of the compile stays uncertain`() {
        // The parser compiles an empty stand-in for the missing BaseSpec; a stub is not evidence.
        val foo = verdict("class FooSpec extends BaseSpec {}", name = "FooSpec")
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
    }

    @Test
    fun `an unresolved link anywhere in the chain stays uncertain`() {
        val others = mapOf("BaseSpec.groovy" to "class BaseSpec extends MissingBase {}")
        val foo = verdict("class FooSpec extends BaseSpec {}", others, "FooSpec")
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
    }

    @Test
    fun `a stubbed Specification without Spock on the classpath stays uncertain`() {
        val withoutSpock = object : ClassLoader(SpockCompiledHierarchyTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("spock.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }

            override fun getResource(name: String) = if (name.startsWith("spock/")) null else super.getResource(name)
        }
        val foo = verdict("class FooSpec extends BaseSpec {}", baseSpec, "FooSpec", withoutSpock)
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
    }

    // The parse hung on any inheritance cycle before #1378 (#1369). Preemptive timeouts run the body on another
    // thread, so a tight loop fails the test instead of stalling the suite.

    @Test
    fun `a cycle through open sources terminates as uncertain once the failing source is excluded`() {
        val others = mapOf("A.groovy" to "class A extends B {}", "B.groovy" to "class B extends Probe {}")
        // Parse and classify inside the timeout: the classifier's walk is part of what this guards.
        val (result, probe) = assertTimeoutPreemptively(CYCLE_TIMEOUT) {
            parse("class Probe extends A {}", openSources = others).let { it to probeOf(it) }
        }

        // The first compile reports the cycle; the facade excludes the failing source and recompiles without it.
        assertTrue(result.outcome.excludedSources.isNotEmpty(), "excluded: ${result.outcome.excludedSources}")
        assertEquals(SpecificationStatus.UNCERTAIN, probe.status)
        assertEquals(SpecificationEvidence.UNRESOLVED_SUPERCLASS, probe.evidence)
    }

    @Test
    fun `a cycle through source roots reaches the final AST and terminates as uncertain`(@TempDir roots: Path) {
        Files.writeString(roots.resolve("A.groovy"), "class A extends B {}")
        Files.writeString(roots.resolve("B.groovy"), "class B extends Probe {}")
        val (result, probe) = assertTimeoutPreemptively(CYCLE_TIMEOUT) {
            parse("class Probe extends A {}", roots = listOf(roots)).let { it to probeOf(it) }
        }

        // Measured on CI: achievedPhase 4, no fallback, nothing excluded, so the cycle reaches the final AST the
        // classifier walks. With `locatorCandidates` empty, B.groovy's cycle error stays an ERROR diagnostic, so the
        // no-collected-error COMPILATION_FAILED retry does not fire. Nothing is excluded because B.groovy comes only
        // from a source root, not from the open or workspace sources the facade can exclude. In production gls also
        // passes B.groovy as a workspace source (CompilationOrchestrator), so it is excluded there instead; this
        // test pins the cycle-in-AST case.
        assertNull(result.outcome.fallback, "${result.outcome}")
        assertEquals(emptyList(), result.outcome.excludedSources)
        val messages = result.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("ycle") }, "diagnostics: $messages")
        assertTrue(superclassesRevisit(probe.classNode), "the final AST should keep the A -> B -> Probe cycle")
        assertEquals(SpecificationStatus.UNCERTAIN, probe.status)
        assertEquals(SpecificationEvidence.UNRESOLVED_SUPERCLASS, probe.evidence)
    }

    @Test
    fun `a cycle across modules of one compilation unit stops the compiled chain walk`() {
        // Pin the compiled chain walk directly: Probe -> A -> B -> A, with A and B primary classes of another
        // module in the same unit, plus an acyclic control showing the walk is what decides.
        val (cyclic, cycleProbe) = crossModuleChain(cycle = true)
        val verdict = assertTimeoutPreemptively(CYCLE_TIMEOUT) {
            SpockSourceContext.from(cyclic, isPartial = false).classes.single { it.classNode === cycleProbe }
        }
        assertEquals(SpecificationStatus.UNCERTAIN, verdict.status)
        assertEquals(SpecificationEvidence.UNRESOLVED_SUPERCLASS, verdict.evidence)

        // Control: the same construction without the cycle is decided by the compiled chain walk.
        val (acyclic, plainProbe) = crossModuleChain(cycle = false)
        val control = SpockSourceContext.from(acyclic, isPartial = false).classes
            .single { it.classNode === plainProbe }
        assertEquals(SpecificationStatus.NOT_SPECIFICATION, control.status)
        assertEquals(SpecificationEvidence.COMPILED_SOURCE_HIERARCHY, control.evidence)
    }

    private fun parse(
        content: String,
        openSources: Map<String, String> = emptyMap(),
        roots: List<Path> = emptyList(),
    ): ParseResult = GroovyParserFacade(SpockCompiledHierarchyTest::class.java.classLoader).parse(
        ParseRequest(
            uri = URI.create("file:///src/Probe.groovy"),
            content = content,
            sourceRoots = roots,
            openSources = openSources.mapKeys { (file, _) -> URI.create("file:///src/$file") },
            compilePhase = Phases.SEMANTIC_ANALYSIS,
        ),
    )

    /** Whether following superclass redirects from [node] comes back to a class already seen (bounded walk). */
    private fun superclassesRevisit(node: ClassNode): Boolean {
        val seen: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())
        var link: ClassNode? = node.redirect()
        repeat(MAX_WALK) {
            val current = link ?: return false
            if (!seen.add(current)) return true
            link = current.superClass?.redirect()
        }
        return false
    }

    private fun probeOf(result: ParseResult): SpockClassContext =
        contexts(result).single { it.classNode.name == "Probe" }

    private fun contexts(result: ParseResult): List<SpockClassContext> =
        assertIs<SpockSourceContext.Available>(SpockSourceContext.from(result)).classes

    /** Probe in one module extending A in another module of the same unit; A extends B, which extends A or Object. */
    private fun crossModuleChain(cycle: Boolean): Pair<ModuleNode, ClassNode> {
        val unit = CompileUnit(GroovyClassLoader(), CompilerConfiguration())
        val own = ModuleNode(unit)
        val other = ModuleNode(unit)
        val b = ClassNode("B", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val a = ClassNode("A", Modifier.PUBLIC, b)
        if (cycle) b.superClass = a
        val probe = ClassNode("Probe", Modifier.PUBLIC, a)
        other.addClass(a)
        other.addClass(b)
        own.addClass(probe)
        unit.addModule(own)
        unit.addModule(other)
        return own to probe
    }

    private companion object {
        val CYCLE_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val MAX_WALK = 16
    }
}
