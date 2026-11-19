// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkPolicyTest {
    private val fixtures = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/network-policy/conformance.json")).readText(),
    ).jsonObject

    @Test
    fun `shared policy fixtures enforce configuration and requests`() {
        assertEquals(1, fixtures.getValue("schemaVersion").jsonPrimitive.content.toInt())
        for (case in fixtures.getValue("configs").jsonArray) {
            val item = case.jsonObject
            val snapshot = NetworkPolicy.parse(item["input"], 0)
            assertEquals(item.getValue("valid").jsonPrimitive.content.toBoolean(), snapshot.valid, item.toString())
            assertEquals(item.getValue("mode").jsonPrimitive.content, snapshot.mode.value, item.toString())
            val origins = item.getValue("allowedOrigins").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(origins, snapshot.allowedOrigins, item.toString())
        }
        for (case in fixtures.getValue("origins").jsonArray) {
            val item = case.jsonObject
            val expected = item["normalized"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            assertEquals(expected, normalizeOrigin(item.getValue("input").jsonPrimitive.content), item.toString())
        }
        for (case in fixtures.getValue("requests").jsonArray) {
            val item = case.jsonObject
            val expected = item["code"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val policy = NetworkPolicy.parse(item["policy"], 0)
            assertEquals(expected, policy.authorize(item.getValue("url").jsonPrimitive.content)?.name, item.toString())
        }
    }

    @Test
    fun `replacement revokes admissions and fences publication`() {
        val controller = NetworkPolicyController(
            Json.parseToJsonElement("""{"mode":"connected","allowedOrigins":["https://example.com"]}"""),
        )
        var cancellations = 0
        val lease = assertNotNull(controller.admit("https://example.com/path") { cancellations++ }.lease)
        assertTrue(lease.publish { })
        controller.replace(null)
        assertEquals(1, cancellations)
        assertFalse(lease.publish { error("stale publication") })
        assertEquals(PolicyFailure.STALE_POLICY, lease.check())
        lease.close()
    }

    @Test
    fun `callback failures do not prevent other cancellations`() {
        val controller = NetworkPolicyController(
            Json.parseToJsonElement("""{"mode":"connected","allowedOrigins":["https://example.com"]}"""),
        )
        var cancelled = false
        val callbackFailure = AssertionError("callback")
        controller.admit("https://example.com") { throw callbackFailure }
        controller.admit("https://example.com") { cancelled = true }
        val failures = controller.replace(null)
        assertEquals(listOf(callbackFailure), failures)
        assertTrue(cancelled)
        assertNull(controller.admit("https://example.com") {}.lease)
    }

    @Test
    fun `replacement waits for an already admitted publication`() {
        val controller = NetworkPolicyController(
            Json.parseToJsonElement("""{"mode":"connected","allowedOrigins":["https://example.com"]}"""),
        )
        val lease = assertNotNull(controller.admit("https://example.com") {}.lease)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replaced = CountDownLatch(1)
        val publisher = thread {
            lease.publish {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val replacer = thread {
            controller.replace(null)
            replaced.countDown()
        }
        assertFalse(replaced.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        publisher.join(5000)
        replacer.join(5000)
        assertTrue(replaced.await(1, TimeUnit.SECONDS))
        assertEquals(PolicyFailure.STALE_POLICY, lease.check())
    }

    @Test
    fun `snapshot origins cannot be mutated through a cast`() {
        val snapshot = NetworkPolicy.parse(
            Json.parseToJsonElement("""{"mode":"connected","allowedOrigins":["https://example.com"]}"""),
            1,
        )
        val origins = snapshot.allowedOrigins as MutableSet<String>
        kotlin.test.assertFailsWith<UnsupportedOperationException> { origins.add("https://evil.example") }
        assertEquals(PolicyFailure.ORIGIN_NOT_ALLOWED, snapshot.authorize("https://evil.example"))
    }

    @Test
    fun `cancellation callbacks can reenter the controller`() {
        val connected = Json.parseToJsonElement(
            """{"mode":"connected","allowedOrigins":["https://example.com"]}""",
        )
        val controller = NetworkPolicyController(connected)
        var observedRevision = -1L
        val admission = assertNotNull(
            controller.admit("https://example.com") {
                observedRevision = controller.snapshot().revision
                controller.replace(connected)
            }.lease,
        )
        assertTrue(controller.replace(null).isEmpty())
        assertEquals(1L, observedRevision)
        assertEquals(2L, controller.snapshot().revision)
        assertEquals(PolicyFailure.STALE_POLICY, admission.check())
    }

    @Test
    fun `closing admission is idempotent and unregisters cancellation`() {
        val connected = Json.parseToJsonElement(
            """{"mode":"connected","allowedOrigins":["https://example.com"]}""",
        )
        val controller = NetworkPolicyController(connected)
        var cancelled = false
        val lease = assertNotNull(controller.admit("https://example.com") { cancelled = true }.lease)
        lease.close()
        lease.close()
        controller.replace(null)
        assertFalse(cancelled)
        assertEquals(PolicyFailure.STALE_POLICY, lease.check())
    }

    @Test
    fun `policy revisions reject values outside the safe integer range`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { NetworkPolicy.parse(null, -1) }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            NetworkPolicy.parse(null, 9_007_199_254_740_992L)
        }
        assertEquals(9_007_199_254_740_991L, NetworkPolicy.parse(null, 9_007_199_254_740_991L).revision)
    }

    @Test
    fun `request accepts brackets in query`() {
        assertEquals("https://example.com", normalizeOrigin("https://example.com/?q=[x]", originOnly = false))
    }
}
