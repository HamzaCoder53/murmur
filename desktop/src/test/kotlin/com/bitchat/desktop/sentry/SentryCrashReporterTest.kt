package com.bitchat.desktop.sentry

import com.bitchat.desktop.TestDevices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryCrashReporterTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `scrubber redacts paths, hex keys, peer IDs, IPs, coordinates, and sensitive context keys`() {
        val raw = "Failed for /Users/alice/Skein peer 0123456789abcdef at 192.168.1.42 (37.7749, -122.4194)"
        val scrubbed = SentryScrubber.scrubText(raw)
        assertFalse(scrubbed.contains("alice"))
        assertFalse(scrubbed.contains("0123456789abcdef"))
        assertFalse(scrubbed.contains("192.168.1.42"))
        assertFalse(scrubbed.contains("37.7749"))

        val ctx = SentryScrubber.scrubContext(
            mapOf(
                "nickname" to "secret-nick",
                "message" to "secret-msg",
                "screen" to "settings",
            ),
        )
        assertEquals("<redacted>", ctx["nickname"])
        assertEquals("<redacted>", ctx["message"])
        assertEquals("settings", ctx["screen"])
    }

    @Test
    fun `crash reporting stays disabled when DSN is unset or opt-in is false`() {
        val ctx = devices.context("sentry")
        val noDsn = SentryCrashReporter(contextProvider = { ctx }, dsnProvider = { null })
        noDsn.init("1.0.0")
        noDsn.setEnabled(true)
        assertFalse(noDsn.isEnabled)

        val withDsn = SentryCrashReporter(
            contextProvider = { ctx },
            dsnProvider = { "https://publickey@o0.ingest.sentry.io/12345" },
        )
        withDsn.init("1.0.0")
        withDsn.setEnabled(false)
        assertFalse(withDsn.isEnabled)

        withDsn.setEnabled(true)
        assertTrue(withDsn.isEnabled)

        val envelope = withDsn.buildEnvelope(
            IllegalStateException("Error reading /Users/bob/key 0123456789abcdef0123456789abcdef"),
            mapOf("peer_id" to "deadbeef01234567", "stage" to "handshake"),
        )
        assertFalse(envelope.contains("bob"))
        assertFalse(envelope.contains("0123456789abcdef"))
        assertFalse(envelope.contains("deadbeef01234567"))
        assertTrue(envelope.contains("handshake"))
    }

    @Test
    fun `envelope names the DSN, omits exception text, and only https DSNs are accepted`() {
        val ctx = devices.context("sentry2")
        val dsn = "https://publickey@o0.ingest.sentry.io/12345"
        val reporter = SentryCrashReporter(contextProvider = { ctx }, dsnProvider = { dsn })
        reporter.init("1.2.3")
        assertTrue(reporter.isAvailable)
        val envelope = reporter.buildEnvelope(IllegalStateException("nickname Bob said: meet at the bridge fe80::1"), emptyMap())
        val header = org.json.JSONObject(envelope.lineSequence().first())
        assertEquals(dsn, header.getString("dsn"))
        assertFalse(envelope.contains("Bob"))
        assertFalse(envelope.contains("bridge"))
        assertFalse(envelope.contains("fe80"))
        assertTrue(envelope.contains("java.lang.IllegalStateException"))
        assertTrue(envelope.contains("1.2.3"))

        val plain = SentryCrashReporter(contextProvider = { ctx }, dsnProvider = { "http://publickey@sentry.example/1" })
        plain.init("1.2.3")
        assertFalse(plain.isAvailable)
        plain.setEnabled(true)
        assertFalse(plain.isEnabled)
    }
}
