package com.bitchat.desktop.platform

import com.bitchat.desktop.shim.OwnerOnlyFiles
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * macOS Keychain-backed [SecureKeyStore]. Uses `/usr/bin/security -i` (interactive stdin)
 * so key material never appears in process command-line arguments.
 */
class MacKeychainKeyStore(
    private val service: String = "xfuturehb.murmur.desktop",
) : SecureKeyStore {
    private val lock = Any()

    override fun getOrCreateKey(alias: String): ByteArray = synchronized(lock) {
        val safeAlias = sanitizeAlias(alias)
        readExisting(safeAlias)?.let { return it }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val hex = key.joinToString("") { "%02x".format(it) }
        val proc = ProcessBuilder("/usr/bin/security", "-i")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.bufferedWriter().use { w ->
            w.write("add-generic-password -U -s ${quote(service)} -a ${quote(safeAlias)} -w ${quote(hex)}\n")
            w.flush()
        }
        val code = proc.waitFor()
        check(code == 0) { "macOS Keychain add-generic-password failed (exit $code)" }
        key
    }

    override fun delete(alias: String): Boolean = synchronized(lock) {
        val safeAlias = sanitizeAlias(alias)
        val proc = ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", service, "-a", safeAlias)
            .redirectErrorStream(true)
            .start()
        proc.waitFor() == 0
    }

    private fun sanitizeAlias(alias: String): String =
        alias.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun readExisting(alias: String): ByteArray? {
        val proc = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-a", alias, "-w")
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        if (code != 0) return null
        check(out.length == 64 && out.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Malformed Keychain item for alias '$alias'"
        }
        return ByteArray(32) { i -> out.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Windows DPAPI-backed [SecureKeyStore]. Encrypts each 256-bit key with CurrentUser DPAPI
 * (`ProtectedData.Protect`) and stores the opaque ciphertext blob under `%LOCALAPPDATA%\Skein\secrets`.
 */
class WindowsDpapiKeyStore(private val dir: File) : SecureKeyStore {
    private val lock = Any()

    override fun getOrCreateKey(alias: String): ByteArray = synchronized(lock) {
        val file = fileFor(alias)
        if (file.exists()) {
            val blobB64 = Base64.getEncoder().encodeToString(file.readBytes())
            val plainB64 = runPowerShell(
                "\$b=[Convert]::FromBase64String('$blobB64');" +
                    "\$p=[System.Security.Cryptography.ProtectedData]::Unprotect(\$b,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);" +
                    "[Convert]::ToBase64String(\$p)",
            ).trim()
            val decoded = Base64.getDecoder().decode(plainB64)
            check(decoded.size == 32) { "Malformed DPAPI key for alias '$alias'" }
            return decoded
        }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyB64 = Base64.getEncoder().encodeToString(key)
        val blobB64 = runPowerShell(
            "\$p=[Convert]::FromBase64String('$keyB64');" +
                "\$b=[System.Security.Cryptography.ProtectedData]::Protect(\$p,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);" +
                "[Convert]::ToBase64String(\$b)",
        ).trim()
        val blob = Base64.getDecoder().decode(blobB64)
        OwnerOnlyFiles.ensureDir(dir)
        OwnerOnlyFiles.writeAtomically(file, blob)
        key
    }

    override fun delete(alias: String): Boolean = synchronized(lock) { fileFor(alias).delete() }

    private fun fileFor(alias: String): File {
        val safe = alias.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.dpapi")
    }

    private fun runPowerShell(script: String): String {
        val full = "Add-Type -AssemblyName System.Security; $script"
        val encoded = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) { "Windows DPAPI helper failed (exit $code)" }
        return out
    }
}
