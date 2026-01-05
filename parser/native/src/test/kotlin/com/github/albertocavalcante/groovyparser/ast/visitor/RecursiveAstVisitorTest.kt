package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecursiveAstVisitorTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `visit while loop`() {
        val code = """
            while (true) {
                println "looping"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        // Verify WhileStatement is tracked
        val whileStmts = allNodes.filterIsInstance<WhileStatement>()
        assertEquals(1, whileStmts.size, "Should have tracked exactly one WhileStatement")

        // Verify loop body is tracked
        val whileStmt = whileStmts[0]
        val loopBody = whileStmt.loopBlock
        assertNotNull(loopBody, "While loop should have a body")
        assertTrue(allNodes.contains(loopBody), "Loop body should be tracked")

        // Verify body has parent relationship (body is not synthetic, so it should have a parent)
        assertNotNull(astModel.getParent(loopBody), "Loop body should have a parent")
    }

    @Test
    fun `visit do while loop`() {
        val code = """
            do {
                println "once"
            } while (false)
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        // Verify DoWhileStatement is tracked
        val doWhileStmts = allNodes.filterIsInstance<DoWhileStatement>()
        assertEquals(1, doWhileStmts.size, "Should have tracked exactly one DoWhileStatement")

        // Verify loop body is tracked
        val doWhileStmt = doWhileStmts[0]
        val loopBody = doWhileStmt.loopBlock
        assertNotNull(loopBody, "Do-while loop should have a body")
        assertTrue(allNodes.contains(loopBody), "Loop body should be tracked")

        // Verify body has parent relationship (body is not synthetic, so it should have a parent)
        assertNotNull(astModel.getParent(loopBody), "Loop body should have a parent")
    }

    @Test
    fun `visit try catch finally`() {
        val code = """
            try {
                throw new Exception()
            } catch (Exception e) {
                println "caught"
            } finally {
                println "done"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        // Verify TryCatchStatement is tracked
        val tryStmts = allNodes.filterIsInstance<TryCatchStatement>()
        assertEquals(1, tryStmts.size, "Should have tracked exactly one TryCatchStatement")

        val tryStmt = tryStmts[0]
        assertNotNull(astModel.getParent(tryStmt), "TryCatchStatement should have a parent")

        // Verify catch statements are tracked (most important for this visitor)
        val catchStmts = allNodes.filterIsInstance<org.codehaus.groovy.ast.stmt.CatchStatement>()
        assertTrue(catchStmts.size >= 1, "Should have tracked at least one CatchStatement")

        // Verify throw statement inside try block is tracked
        val throwStmts = allNodes.filterIsInstance<org.codehaus.groovy.ast.stmt.ThrowStatement>()
        assertTrue(throwStmts.size >= 1, "Should have tracked throw statement inside try block")

        // Verify catch statements have parent relationships
        catchStmts.forEach { catchStmt ->
            assertNotNull(astModel.getParent(catchStmt), "Each CatchStatement should have a parent")
        }
    }

    @Test
    fun `visit switch statement`() {
        val code = """
            def x = 1
            switch (x) {
                case 1:
                    println "one"
                    break
                default:
                    println "other"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()

        // Verify SwitchStatement is tracked
        val switchStmts = allNodes.filterIsInstance<SwitchStatement>()
        assertEquals(1, switchStmts.size, "Should have tracked exactly one SwitchStatement")

        // Verify CaseStatement nodes are tracked
        val caseStmts = allNodes.filterIsInstance<CaseStatement>()
        assertTrue(caseStmts.size >= 1, "Should have tracked at least one CaseStatement")

        // Verify BreakStatement is tracked
        val breakStmts = allNodes.filterIsInstance<BreakStatement>()
        assertTrue(breakStmts.size >= 1, "Should have tracked at least one BreakStatement")

        // Verify tracked case statements have parent relationships
        caseStmts.forEach { caseStmt ->
            assertNotNull(astModel.getParent(caseStmt), "Each tracked CaseStatement should have a parent")
        }

        // Verify tracked break statements have parent relationships
        breakStmts.forEach { breakStmt ->
            assertNotNull(astModel.getParent(breakStmt), "Each tracked BreakStatement should have a parent")
        }
    }
}
