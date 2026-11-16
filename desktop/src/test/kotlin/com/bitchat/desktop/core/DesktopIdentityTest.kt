package com.bitchat.desktop.core

import com.bitchat.desktop.TestDevices
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DesktopIdentityTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `identity created on first run is loaded unchanged on the next run`() {
        val first = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val second = DesktopIdentity.loadOrCreate(devices.context("alice"))

        assertEquals(first.peerID, second.peerID)
        assertEquals(first.fingerprint, second.fingerprint)
        assertArrayEquals(first.encryptionService.getStaticPublicKey(), second.encryptionService.getStaticPublicKey())
        assertArrayEquals(first.encryptionService.getSigningPublicKey(), second.encryptionService.getSigningPublicKey())
        assertEquals(first.nickname, second.nickname)
    }

    @Test
    fun `peer ID is the first 8 bytes of SHA-256 of the Noise static key, as on Android`() {
        val identity = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.encryptionService.getStaticPublicKey()!!)
        val expected = digest.take(8).joinToString("") { "%02x".format(it) }
        assertEquals(expected, identity.peerID)
        assertEquals(16, identity.peerID.length)
    }

    @Test
    fun `separate devices get separate identities`() {
        val alice = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val bob = DesktopIdentity.loadOrCreate(devices.context("bob"))
        assertNotEquals(alice.peerID, bob.peerID)
    }

    @Test
    fun `renaming keeps the peer ID`() {
        val identity = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val before = identity.peerID
        identity.nickname = "synthetic-alice"
        val reloaded = DesktopIdentity.loadOrCreate(devices.context("alice"))
        assertEquals("synthetic-alice", reloaded.nickname)
        assertEquals(before, reloaded.peerID)
    }

    @Test
    fun `identity keys are stored only in encrypted preference files`() {
        val identity = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val staticKey = identity.encryptionService.getStaticPublicKey()!!
        val prefsDir = File(devices.root, "alice/shared_prefs")
        val files = prefsDir.listFiles().orEmpty()
        assertTrue("expected encrypted stores", files.any { it.name.endsWith(".enc") })
        val hex = staticKey.joinToString("") { "%02x".format(it) }
        val b64 = java.util.Base64.getEncoder().encodeToString(staticKey)
        for (f in files.filter { it.name.endsWith(".enc") }) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse("${f.name} leaks key material", text.contains(hex) || text.contains(b64.take(20)))
        }
    }
}
