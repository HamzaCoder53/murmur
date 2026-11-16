package com.bitchat.desktop.core

import com.bitchat.desktop.TestDevices
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Noise XX (Noise_XX_25519_ChaChaPoly_SHA256) between two shared EncryptionService instances. */
class NoiseHandshakeOnJvmTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `XX handshake completes in three messages and both sides authenticate each other`() {
        val alice = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val bob = DesktopIdentity.loadOrCreate(devices.context("bob"))
        val a = alice.encryptionService
        val b = bob.encryptionService

        val m1 = a.initiateHandshake(bob.peerID)
        assertNotNull("message 1 (-> e)", m1)
        val m2 = b.processHandshakeMessage(m1!!, alice.peerID)
        assertNotNull("message 2 (<- e, ee, s, es)", m2)
        val m3 = a.processHandshakeMessage(m2!!, bob.peerID)
        assertNotNull("message 3 (-> s, se)", m3)
        assertNull("responder sends nothing after message 3", b.processHandshakeMessage(m3!!, alice.peerID))

        assertTrue(a.hasEstablishedSession(bob.peerID))
        assertTrue(b.hasEstablishedSession(alice.peerID))
        assertArrayEquals(bob.encryptionService.getStaticPublicKey(), a.getAuthenticatedRemoteStaticKey(bob.peerID))
        assertArrayEquals(alice.encryptionService.getStaticPublicKey(), b.getAuthenticatedRemoteStaticKey(alice.peerID))
        assertEquals(bob.fingerprint, a.getPeerFingerprint(bob.peerID))
    }

    @Test
    fun `transport messages round trip both ways and tampering is rejected`() {
        val alice = DesktopIdentity.loadOrCreate(devices.context("alice"))
        val bob = DesktopIdentity.loadOrCreate(devices.context("bob"))
        handshake(alice, bob)

        val hello = "synthetic hello".toByteArray()
        val sealed = alice.encryptionService.encrypt(hello, bob.peerID)
        assertFalse(sealed.contentEquals(hello))
        assertArrayEquals(hello, bob.encryptionService.decrypt(sealed, alice.peerID))

        val reply = "synthetic reply".toByteArray()
        assertArrayEquals(reply, alice.encryptionService.decrypt(bob.encryptionService.encrypt(reply, alice.peerID), bob.peerID))

        val tampered = alice.encryptionService.encrypt(hello, bob.peerID).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val rejected = try {
            bob.encryptionService.decrypt(tampered, alice.peerID)
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("tampered ciphertext must not decrypt", rejected)
    }

    private fun handshake(alice: DesktopIdentity, bob: DesktopIdentity) {
        val m1 = alice.encryptionService.initiateHandshake(bob.peerID)!!
        val m2 = bob.encryptionService.processHandshakeMessage(m1, alice.peerID)!!
        val m3 = alice.encryptionService.processHandshakeMessage(m2, bob.peerID)!!
        bob.encryptionService.processHandshakeMessage(m3, alice.peerID)
        check(alice.encryptionService.hasEstablishedSession(bob.peerID) && bob.encryptionService.hasEstablishedSession(alice.peerID))
    }
}
