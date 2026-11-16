package com.bitchat.desktop.core

import com.bitchat.desktop.TestDevices
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.FileKeyStore
import com.bitchat.desktop.shim.ShimRuntime
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
        ShimRuntime.resetCachesForTests()
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
        ShimRuntime.resetCachesForTests()
        val reloaded = DesktopIdentity.loadOrCreate(devices.context("alice"))
        assertEquals("synthetic-alice", reloaded.nickname)
        assertEquals(before, reloaded.peerID)
    }

    @Test
    fun `identity keys are stored only in encrypted preference files`() {
        val identity = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val staticPub = identity.encryptionService.getStaticPublicKey()!!
        val signingPub = identity.encryptionService.getSigningPublicKey()!!
        val prefsDir = File(devices.root, "alice/shared_prefs")
        val prefsFiles = prefsDir.listFiles().orEmpty()
        assertTrue("expected encrypted stores", prefsFiles.any { it.name.endsWith(".enc") })

        val needles = listOf(staticPub, signingPub).flatMap { k ->
            listOf(
                k.joinToString("") { "%02x".format(it) },
                java.util.Base64.getEncoder().encodeToString(k).take(20),
            )
        }
        val allFiles = devices.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(allFiles.isNotEmpty())
        for (f in allFiles) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            for (needle in needles) {
                assertFalse("${f.name} leaks key material", text.contains(needle))
            }
        }
    }

    @Test
    fun `default host platform never uses plaintext FileKeyStore without opt-in`() {
        val prev = System.getProperty(DesktopPlatform.ALLOW_INSECURE_KEYSTORE_PROP)
        try {
            System.clearProperty(DesktopPlatform.ALLOW_INSECURE_KEYSTORE_PROP)
            val platform = DesktopPlatform.forCurrentHost(File(devices.root, "host"))
            assertFalse(platform.keyStore is FileKeyStore)
        } finally {
            if (prev != null) System.setProperty(DesktopPlatform.ALLOW_INSECURE_KEYSTORE_PROP, prev)
        }
    }

    @Test
    fun `FileKeyStore persists 32-byte keys and rejects malformed key files`() {
        val secretsDir = File(devices.root, "secrets")
        val store = FileKeyStore(secretsDir)
        val created = store.getOrCreateKey("master")
        assertEquals(32, created.size)
        assertArrayEquals(created, store.getOrCreateKey("master"))

        val keyFile = File(secretsDir, "master.key")
        keyFile.writeBytes(byteArrayOf(1, 2, 3))
        var threw = false
        try {
            store.getOrCreateKey("master")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected malformed key file to throw instead of regenerating", threw)
    }
}
