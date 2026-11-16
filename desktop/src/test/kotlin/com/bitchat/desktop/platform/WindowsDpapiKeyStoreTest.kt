package com.bitchat.desktop.platform

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The DPAPI store must never put key material on the PowerShell command line. DPAPI itself only
 * exists on Windows, so the protect/unprotect step is faked here; the stdin plumbing is checked
 * against a POSIX shell on hosts that have one.
 */
class WindowsDpapiKeyStoreTest {
    private val dir: File = Files.createTempDirectory("skein-dpapi-test").toFile()

    @After fun tearDown() { dir.deleteRecursively() }

    /** Stand-in for DPAPI: XOR with a constant, recording every command line it was given. */
    private class FakeDpapi {
        val commands = mutableListOf<List<String>>()
        val inputs = mutableListOf<ByteArray>()
        fun run(command: List<String>, input: ByteArray): ByteArray {
            commands += command
            inputs += input.copyOf()
            return ByteArray(input.size) { (input[it].toInt() xor 0x5a).toByte() }
        }
    }

    private fun decodedScripts(commands: List<List<String>>): List<String> = commands.map { cmd ->
        val idx = cmd.indexOf("-EncodedCommand")
        String(Base64.getDecoder().decode(cmd[idx + 1]), Charsets.UTF_16LE)
    }

    @Test
    fun `new key goes over stdin and never appears in the command line`() {
        val fake = FakeDpapi()
        val store = WindowsDpapiKeyStore(dir, fake::run)
        val key = store.getOrCreateKey("synthetic-alias")

        assertEquals(32, key.size)
        assertArrayEquals("the key is handed to DPAPI on stdin", key, fake.inputs.single())
        val keyB64 = Base64.getEncoder().encodeToString(key)
        val keyHex = key.joinToString("") { "%02x".format(it) }
        for (script in decodedScripts(fake.commands) + fake.commands.flatten()) {
            assertFalse(script.contains(keyB64))
            assertFalse(script.contains(keyHex))
        }
        assertEquals(WindowsDpapiKeyStore.PROTECT_SCRIPT, decodedScripts(fake.commands).single().substringAfter("; "))
    }

    @Test
    fun `stored blob is unprotected over stdin and returns the same key`() {
        val fake = FakeDpapi()
        val created = WindowsDpapiKeyStore(dir, fake::run).getOrCreateKey("synthetic-alias")
        val reloaded = WindowsDpapiKeyStore(dir, fake::run).getOrCreateKey("synthetic-alias")

        assertArrayEquals(created, reloaded)
        val blob = File(dir, "synthetic-alias.dpapi").readBytes()
        assertFalse("the file holds the protected blob, not the key", blob.contentEquals(created))
        assertArrayEquals(blob, fake.inputs.last())
        val blobB64 = Base64.getEncoder().encodeToString(blob)
        assertFalse(decodedScripts(fake.commands).last().contains(blobB64))
        assertEquals(WindowsDpapiKeyStore.UNPROTECT_SCRIPT, decodedScripts(fake.commands).last().substringAfter("; "))
    }

    @Test
    fun `helper reads stdin, ignores stderr noise and decodes the last stdout line`() {
        assumeTrue(File("/bin/sh").canExecute())
        val input = ByteArray(32) { it.toByte() }
        // Echoes the base64 line back after printing noise on both streams.
        val out = WindowsDpapiKeyStore.runStdinHelper(
            listOf("/bin/sh", "-c", "read line; echo 'WARNING: synthetic' >&2; echo; echo \"\$line\""),
            input,
        )
        assertArrayEquals(input, out)
    }
}
