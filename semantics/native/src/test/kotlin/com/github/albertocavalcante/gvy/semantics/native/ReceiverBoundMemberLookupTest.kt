// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiverBoundMemberLookupTest {
    @Test
    fun `outer method and nested property access keep their own compiler-selected receivers`() {
        val uri = URI.create("file:///Client.groovy")
        val source = "class Client { def use(Peer peer, Arg arg) { peer.pick(arg.value) } }"
        val request = ParseRequest(
            uri,
            source,
            openSources = mapOf(
                URI.create("file:///Peer.groovy") to "class Peer { String pick(Integer x) { 'ok' } }",
                URI.create("file:///Arg.groovy") to "class Arg { Integer value }",
            ),
            compilePhase = Phases.CANONICALIZATION,
        )
        val parsed = GroovyParserFacade().parse(request)
        try {
            val module = assertNotNull(parsed.ast)
            val call = parsed.astModel.getNodes(uri).filterIsInstance<MethodCallExpression>()
                .single { it.methodAsString == "pick" }
            val reads = mutableListOf<String>()
            val members = CheckedMembers(null, reads)
            val semantics = GroovySemantics(ReflectionTypeSolver(), workspaceMemberLookup = members)

            assertEquals(TypeConstants.STRING, semantics.resolveType(call, module))
            assertEquals(listOf("Arg.value", "Peer.pick"), reads)
        } finally {
            parsed.compilationUnit.classLoader.close()
        }
    }

    private class CheckedMembers(private val receiver: ClassNode?, private val reads: MutableList<String>) :
        ReceiverBoundMemberLookup {
        override fun forReceiver(declaration: ClassNode?): MemberLookup = CheckedMembers(declaration, reads)

        override fun findField(classFqn: String, fieldName: String): MemberInfo {
            checkOwner(classFqn, fieldName)
            return MemberInfo(fieldName, SymbolKind.FIELD, TypeConstants.INT, null, "Arg#value.")
        }

        override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo {
            checkOwner(classFqn, methodName)
            return MemberInfo(methodName, SymbolKind.METHOD, TypeConstants.STRING, null, "Peer#pick().")
        }

        override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> =
            error("Member enumeration is unnecessary")

        private fun checkOwner(name: String, member: String) {
            val owner = assertNotNull(receiver, "Expected a compiler-selected receiver for $name.$member")
            assertTrue(owner.isPrimaryClassNode)
            assertEquals(name, owner.name)
            reads.add("$name.$member")
        }
    }
}
