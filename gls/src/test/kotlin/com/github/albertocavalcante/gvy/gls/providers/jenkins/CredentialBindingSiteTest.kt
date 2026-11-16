// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.jenkins

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.jenkins.metadata.Availability
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.OwnerEvidence
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol
import com.github.albertocavalcante.gvy.jenkins.metadata.VersionedMetadataLoader
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CredentialBindingSiteTest {
    private val uri = URI.create("file:///Jenkinsfile")
    private val metadata = VersionedMetadataLoader().loadMerged()
    private val capabilities = mockk<JenkinsCapabilities> {
        every { getGlobalVariables() } returns emptyList()
    }

    @Test
    fun `parsed binding list is the first tuple argument of the wrapper call`() {
        val parsed = parse()
        val call = parsed.astModel.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        val list = assertIs<ListExpression>(parsed.astModel.getParent(call))
        val outer = assertIs<MethodCallExpression>(parsed.astModel.getParent(list))
        val arguments = assertIs<TupleExpression>(outer.arguments)

        assertEquals("withCredentials", outer.methodAsString)
        assertSame(call, list.expressions.single())
        assertSame(list, arguments.expressions.firstOrNull())
    }

    @Test
    fun `degraded evidence rejects an otherwise valid parsed binding`() {
        val parsed = parse()
        val call = parsed.astModel.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        assertTrue(parsed.isSuccessful)
        assertFalse(parsed.outcome.isDegraded)
        assertEquals(
            CredentialBindingSite.BINDING,
            parsed.astModel.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            parsed.astModel.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = false),
        )
    }

    @Test
    fun `missing script scope and executable collision both fail closed`() {
        val parsed = parse()
        val call = parsed.astModel.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        assertEquals(
            CredentialBindingSite.BINDING,
            parsed.astModel.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )
        val noScope = spyk(parsed.astModel)
        every { noScope.getAllClassNodes() } returns emptyList()
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            noScope.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )

        val collision = metadata.withCustomStringStep()
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            parsed.astModel.credentialBindingSite(call, uri, collision, capabilities, reliableSource = true),
        )
    }

    @Test
    fun `wrapper availability gates binding eligibility without deleting raw facts`() {
        val parsed = parse()
        val call = parsed.astModel.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        for (availability in Availability.entries) {
            val selected = metadata.copy(
                catalogue = metadata.catalogue.copy(
                    symbols = metadata.catalogue.symbols.map { symbol ->
                        if (symbol.kind == SymbolKind.EXECUTABLE_STEP && symbol.name == "withCredentials") {
                            symbol.copy(availability = availability)
                        } else {
                            symbol
                        }
                    },
                ),
            )
            val wrappers = selected.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "withCredentials")
            assertEquals(availability, wrappers.single().availability)
            val expected = if (availability == Availability.UNAVAILABLE) {
                CredentialBindingSite.UNKNOWN
            } else {
                CredentialBindingSite.BINDING
            }

            assertEquals(
                expected,
                parsed.astModel.credentialBindingSite(call, uri, selected, capabilities, reliableSource = true),
                "Wrapper availability $availability",
            )
        }
    }

    @Test
    fun `wrapper plugin label requires explicit ownership evidence`() {
        val parsed = parse()
        val call = parsed.astModel.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        assertEquals(
            CredentialBindingSite.BINDING,
            parsed.astModel.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )
        for (evidence in OwnerEvidence.entries) {
            val selected = metadata.copy(
                catalogue = metadata.catalogue.copy(
                    symbols = metadata.catalogue.symbols.map { symbol ->
                        if (symbol.kind == SymbolKind.EXECUTABLE_STEP && symbol.name == "withCredentials") {
                            symbol.copy(ownerEvidence = evidence)
                        } else {
                            symbol
                        }
                    },
                ),
            )
            val expected = if (evidence == OwnerEvidence.UNKNOWN) {
                CredentialBindingSite.UNKNOWN
            } else {
                CredentialBindingSite.BINDING
            }
            assertEquals(
                expected,
                parsed.astModel.credentialBindingSite(call, uri, selected, capabilities, reliableSource = true),
                "Wrapper owner evidence $evidence",
            )
        }
    }

    @Test
    fun `top-level binding fails closed when script ownership or scopes are inconsistent`() {
        val perturbations = ownershipPerturbations() + bodyPerturbations() + scopePerturbations()
        for ((description, perturb) in perturbations) {
            val fixture = scriptFixture()
            assertEquals(
                CredentialBindingSite.BINDING,
                fixture.model.credentialBindingSite(fixture.call, uri, metadata, capabilities, reliableSource = true),
                "Each negative starts from a proven direct-script binding: $description",
            )
            perturb(fixture)
            assertEquals(
                CredentialBindingSite.UNKNOWN,
                fixture.model.credentialBindingSite(fixture.call, uri, metadata, capabilities, reliableSource = true),
                description,
            )
        }
    }

    @Test
    fun `scoped credential binding rejects foreign and unknown source nodes`() {
        val parsed = parse("node { withCredentials([string(credentialsId: 'token', variable: 'T')]) {} }")
        assertTrue(parsed.isSuccessful)
        assertFalse(parsed.outcome.isDegraded)
        val model = spyk(parsed.astModel)
        val call = model.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        val scopes = generateSequence(model.getParent(call)) { model.getParent(it) }
            .filterIsInstance<ClosureExpression>().toList()
        assertTrue(scopes.any { it.variableScope != null }, "The test must exercise the ancestor-scope branch")
        assertEquals(
            CredentialBindingSite.BINDING,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )

        val foreignUri = URI.create("file:///foreign/Jenkinsfile")
        val foreignParsed = GroovyParserFacade().parse(
            ParseRequest(uri = foreignUri, content = "echo 'other'", compilePhase = Phases.CANONICALIZATION),
        )
        val foreignScript = foreignParsed.astModel.getAllClassNodes().single { it.isScriptSafe() }
        val scripts = model.getAllClassNodes() + foreignScript
        every { model.getAllClassNodes() } returns scripts
        every { model.getUri(match { it === foreignScript }) } returns foreignUri
        assertEquals(uri, model.getUri(call))
        assertEquals(foreignUri, model.getUri(foreignScript))
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            model.credentialBindingSite(call, foreignUri, metadata, capabilities, reliableSource = true),
            "A same-document script must not authorize a foreign scoped call",
        )
        every { model.getUri(match { it === call }) } returns null
        assertNull(model.getUri(call))
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
            "An unknown call URI is not ownership evidence",
        )
    }

    @Test
    fun `scoped binding requires every tracked node to belong to the requested document`() {
        val foreign = URI.create("file:///foreign/Jenkinsfile")
        for (nodeKind in listOf("call", "list", "outer", "closure", "script")) {
            for (nodeUri in listOf(null, foreign)) {
                val parsed = parse("node { withCredentials([string(credentialsId: 'token')]) {} }")
                val model = spyk(parsed.astModel)
                val call = model.getAllNodes().filterIsInstance<MethodCallExpression>()
                    .single { it.methodAsString == "string" }
                val list = assertIs<ListExpression>(model.getParent(call))
                val outer = assertIs<MethodCallExpression>(model.getParent(list))
                val ancestors = generateSequence(model.getParent(call)) { model.getParent(it) }.toList()
                val closure = ancestors.filterIsInstance<ClosureExpression>().first()
                val script = ancestors.filterIsInstance<ClassNode>().single()
                assertNotNull(closure.variableScope)
                assertEquals(
                    CredentialBindingSite.BINDING,
                    model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
                    "Positive scoped binding before $nodeKind URI perturbation",
                )
                val node = when (nodeKind) {
                    "call" -> call
                    "list" -> list
                    "outer" -> outer
                    "closure" -> closure
                    else -> script
                }
                every { model.getUri(match { it === node }) } returns nodeUri
                assertEquals(nodeUri, model.getUri(node))
                assertEquals(
                    CredentialBindingSite.UNKNOWN,
                    model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
                    "$nodeKind with URI $nodeUri must fail closed",
                )
            }
        }
    }

    @Test
    fun `scoped binding requires its actual registered enclosing script`() {
        val parsed = parse("node { withCredentials([string(credentialsId: 'token')]) {} }")
        val model = spyk(parsed.astModel)
        val call = model.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        val ancestors = generateSequence(model.getParent(call)) { model.getParent(it) }.toList()
        val closure = ancestors.filterIsInstance<ClosureExpression>().first()
        val owner = ancestors.filterIsInstance<ClassNode>().single()
        assertNotNull(closure.variableScope)
        assertEquals(
            CredentialBindingSite.BINDING,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )
        val otherParsed = parse("echo 'other'")
        val other = otherParsed.astModel.getAllClassNodes().single { it.isScriptSafe() }
        assertTrue(other !== owner)
        every { model.getAllClassNodes() } returns listOf(other)
        every { model.getUri(match { it === other }) } returns uri
        assertSame(other, model.getAllClassNodes().single())
        assertEquals(uri, model.getUri(other))
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
            "Another same-document script cannot authorize an unregistered owner",
        )
        every { model.getAllClassNodes() } returns listOf(owner)
        assertSame(owner, model.getAllClassNodes().single())
        assertEquals(
            CredentialBindingSite.BINDING,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
            "Restore a positive registered owner before detaching the scoped chain",
        )
        every { model.getParent(closure) } returns null
        assertNull(model.getParent(closure))
        assertNotNull(closure.variableScope)
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
            "A scoped orphan cannot borrow an unrelated registered script",
        )
    }

    @Test
    fun `missing encountered ancestor scope is never rescued by script scope`() {
        val parsed = parse("node { withCredentials([string(credentialsId: 'token')]) {} }")
        val model = parsed.astModel
        val call = model.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        val ancestors = generateSequence(model.getParent(call)) { model.getParent(it) }.toList()
        val closure = ancestors.filterIsInstance<ClosureExpression>().first()
        val owner = ancestors.filterIsInstance<ClassNode>().single()
        assertNotNull(closure.variableScope)
        assertTrue(model.getAllClassNodes().any { it === owner })
        assertEquals(uri, model.getUri(closure))
        assertEquals(uri, model.getUri(owner))
        assertEquals(
            CredentialBindingSite.BINDING,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )
        closure.variableScope = null
        assertNull(closure.variableScope)
        assertTrue(model.getAllClassNodes().any { it === owner })
        assertEquals(uri, model.getUri(closure))
        assertEquals(uri, model.getUri(owner))
        assertEquals(
            CredentialBindingSite.UNKNOWN,
            model.credentialBindingSite(call, uri, metadata, capabilities, reliableSource = true),
        )
    }

    private fun ownershipPerturbations(): List<Pair<String, (ScriptFixture) -> Unit>> {
        val foreign = URI.create("file:///foreign/Jenkinsfile")
        return listOf(
            "unknown call URI" to { fixture ->
                every { fixture.model.getUri(fixture.call) } returns null
                assertNull(fixture.model.getUri(fixture.call))
            },
            "unknown root URI" to { fixture ->
                every { fixture.model.getUri(fixture.script) } returns null
                assertNull(fixture.model.getUri(fixture.script))
            },
            "foreign root with another local script" to { fixture ->
                val other = spyk(fixture.script)
                every { fixture.model.getAllClassNodes() } returns listOf(fixture.script, other)
                every { fixture.model.getUri(match { it === fixture.script }) } returns foreign
                every { fixture.model.getUri(match { it === other }) } returns uri
                assertTrue(other !== fixture.script && other.isScriptSafe())
                assertSame(fixture.script, fixture.model.getParent(fixture.statement))
                assertEquals(uri, fixture.model.getUri(other))
                assertEquals(foreign, fixture.model.getUri(fixture.script))
            },
            "unregistered root" to { fixture ->
                every { fixture.model.getAllClassNodes() } returns emptyList()
                assertTrue(fixture.model.getAllClassNodes().isEmpty())
            },
            "mismatched root parent" to { fixture ->
                val other = ClassNode("OtherScript", 0, ClassHelper.OBJECT_TYPE)
                every { fixture.model.getParent(fixture.statement) } returns other
                assertSame(other, fixture.model.getParent(fixture.statement))
            },
        )
    }

    private fun bodyPerturbations(): List<Pair<String, (ScriptFixture) -> Unit>> = listOf(
        "missing body" to { fixture ->
            fixture.script.removeMethod(fixture.body)
            assertFalse(fixture.script.methods.any { it === fixture.body })
        },
        "duplicate body" to { fixture ->
            fixture.script.methods.add(fixture.body)
            assertEquals(2, fixture.script.methods.count { it === fixture.body })
        },
        "static sole body" to { fixture ->
            fixture.body.modifiers = fixture.body.modifiers or Modifier.STATIC
            assertTrue(fixture.body.isStatic)
        },
        "static duplicate body" to { fixture ->
            val body = fixture.body
            val duplicate = MethodNode(
                body.name,
                body.modifiers or Modifier.STATIC,
                body.returnType,
                body.parameters,
                body.exceptions,
                body.code,
            )
            duplicate.setIsScriptBody()
            fixture.script.addMethod(duplicate)
            assertTrue(duplicate.isStatic && duplicate.isScriptBody && duplicate.declaringClass === fixture.script)
            val bodies = fixture.script.methods.count { it.isScriptBody && it.declaringClass === fixture.script }
            assertEquals(2, bodies)
        },
        "foreign body declaration" to { fixture ->
            val other = ClassNode("OtherScript", 0, ClassHelper.OBJECT_TYPE)
            fixture.body.declaringClass = other
            assertSame(other, fixture.body.declaringClass)
        },
        "different body code" to { fixture ->
            val other = BlockStatement()
            fixture.body.code = other
            assertSame(other, fixture.body.code)
        },
        "statement outside root block" to { fixture ->
            fixture.block.statements.clear()
            assertTrue(fixture.block.statements.isEmpty())
        },
        "different statement expression" to { fixture ->
            fixture.statement.expression = ConstantExpression.NULL
            assertSame(ConstantExpression.NULL, fixture.statement.expression)
        },
    )

    private fun scopePerturbations(): List<Pair<String, (ScriptFixture) -> Unit>> = listOf(
        "missing block scope" to { fixture ->
            fixture.block.variableScope = null
            assertNull(fixture.block.variableScope)
        },
        "missing body scope" to { fixture ->
            val body = spyk(fixture.body)
            every { body.variableScope } returns null
            val index = fixture.script.methods.indexOfFirst { it === fixture.body }
            assertTrue(index >= 0)
            fixture.script.methods[index] = body
            assertSame(fixture.script, body.declaringClass)
            assertTrue(body.isScriptBody && !body.isStatic)
            assertSame(fixture.block, body.code)
            val ownBody = fixture.script.methods.single { it.isScriptBody && it.declaringClass === fixture.script }
            assertSame(body, ownBody)
            assertNull(body.variableScope)
        },
        "block shadows binding" to { fixture ->
            fixture.block.variableScope.putDeclaredVariable(VariableExpression("string"))
            assertNotNull(fixture.block.variableScope.getDeclaredVariable("string"))
        },
        "block shadows wrapper" to { fixture ->
            fixture.block.variableScope.putDeclaredVariable(VariableExpression("withCredentials"))
            assertNotNull(fixture.block.variableScope.getDeclaredVariable("withCredentials"))
        },
        "body shadows binding" to { fixture ->
            fixture.body.variableScope.putDeclaredVariable(VariableExpression("string"))
            assertNotNull(fixture.body.variableScope.getDeclaredVariable("string"))
        },
        "body shadows wrapper" to { fixture ->
            fixture.body.variableScope.putDeclaredVariable(VariableExpression("withCredentials"))
            assertNotNull(fixture.body.variableScope.getDeclaredVariable("withCredentials"))
        },
    )

    private data class ScriptFixture(
        val model: GroovyAstModel,
        val call: MethodCallExpression,
        val statement: ExpressionStatement,
        val script: ClassNode,
        val block: BlockStatement,
        val body: MethodNode,
    )

    private fun scriptFixture(): ScriptFixture {
        val parsed = parse()
        assertTrue(parsed.isSuccessful)
        assertFalse(parsed.outcome.isDegraded)
        val model = spyk(parsed.astModel)
        val call = model.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "string" }
        val outer = model.getAllNodes().filterIsInstance<MethodCallExpression>()
            .single { it.methodAsString == "withCredentials" }
        val statement = assertIs<ExpressionStatement>(model.getParent(outer))
        val script = assertIs<ClassNode>(model.getParent(statement))
        val block = script.module.statementBlock
        val body = script.methods.single { it.declaringClass === script && it.isScriptBody }
        return ScriptFixture(model, call, statement, script, block, body)
    }

    private fun parse(content: String = "withCredentials([string(credentialsId: 'token', variable: 'T')]) {}") =
        GroovyParserFacade().parse(
            ParseRequest(
                uri = uri,
                content = content,
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

    private fun MergedJenkinsMetadata.withCustomStringStep(): MergedJenkinsMetadata = copy(
        catalogue = catalogue.copy(
            symbols = catalogue.symbols + TypedSymbol(
                id = "custom-step",
                name = "string",
                kind = SymbolKind.EXECUTABLE_STEP,
            ),
        ),
    )
}
