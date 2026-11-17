package com.bitchat.desktop.platform

import com.bitchat.desktop.shim.OwnerOnlyFiles
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * macOS Keychain-backed [SecureKeyStore]. New keys are written through `/usr/bin/security -i`
 * (commands on stdin) so key material never appears in process command-line arguments.
 *
 * Fails closed: only "item not found" (exit 44, errSecItemNotFound) counts as a missing key. A
 * locked keychain, a denied or cancelled access prompt, or any other error throws instead of
 * minting a new key, and items are never added with `-U`, so an existing key is never replaced.
 */
class MacKeychainKeyStore(
    private val service: String = "xfuturehb.murmur.desktop",
    private val runner: ProcessRunner = SystemProcessRunner,
    private val random: SecureRandom = SecureRandom(),
) : SecureKeyStore {
    private val lock = Any()

    override fun getOrCreateKey(alias: String): ByteArray = synchronized(lock) {
        val safeAlias = sanitizeAlias(alias)
        readExisting(safeAlias)?.let { return it }
        val key = ByteArray(32).also(random::nextBytes)
        val hex = key.joinToString("") { "%02x".format(it) }
        val add = runner.run(
            listOf(SECURITY, "-i"),
            "add-generic-password -s ${quote(service)} -a ${quote(safeAlias)} -w ${quote(hex)}\n",
        )
        when (add.exitCode) {
            0 -> key
            // Someone else created the item between our read and our add: use theirs.
            EXIT_DUPLICATE_ITEM -> readExisting(safeAlias)
                ?: error("macOS Keychain item for '$safeAlias' vanished after a duplicate-item error")
            else -> error("macOS Keychain add-generic-password failed (exit ${add.exitCode})")
        }
    }

    override fun delete(alias: String): Boolean = synchronized(lock) {
        val safeAlias = sanitizeAlias(alias)
        runner.run(listOf(SECURITY, "delete-generic-password", "-s", service, "-a", safeAlias), null).exitCode == 0
    }

    private fun sanitizeAlias(alias: String): String =
        alias.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** The stored key, or null only when the Keychain reports the item does not exist. */
    private fun readExisting(alias: String): ByteArray? {
        val res = runner.run(listOf(SECURITY, "find-generic-password", "-s", service, "-a", alias, "-w"), null)
        when (res.exitCode) {
            0 -> Unit
            EXIT_ITEM_NOT_FOUND -> return null
            else -> throw IllegalStateException(
                "macOS Keychain read failed (exit ${res.exitCode}); the keychain may be locked or access was denied",
            )
        }
        val out = res.stdout.trim()
        check(out.length == 64 && out.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Malformed Keychain item for alias '$alias'"
        }
        return ByteArray(32) { i -> out.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val SECURITY = "/usr/bin/security"
        /** `security` exits with the low byte of the OSStatus: errSecItemNotFound (-25300). */
        const val EXIT_ITEM_NOT_FOUND = 44
        /** errSecDuplicateItem (-25299). */
        const val EXIT_DUPLICATE_ITEM = 45
    }
}

/**
 * Windows DPAPI-backed [SecureKeyStore]. Encrypts each 256-bit key with CurrentUser DPAPI
 * (`ProtectedData.Protect`) and stores the opaque ciphertext blob under `%LOCALAPPDATA%\Skein\secrets`.
 *
 * The PowerShell script passed on the command line is fixed and holds no data. The key or blob
 * goes to the child over stdin and the result comes back over its stdout pipe, so neither ever
 * appears in argv, process-creation audit events or script-block logs.
 */
class WindowsDpapiKeyStore(
    private val dir: File,
    private val runner: ProcessRunner = SystemProcessRunner,
    private val random: SecureRandom = SecureRandom(),
) : SecureKeyStore {
    private val lock = Any()

    override fun getOrCreateKey(alias: String): ByteArray = synchronized(lock) {
        val file = fileFor(alias)
        if (file.exists()) {
            val decoded = dpapi("U", file.readBytes())
            check(decoded.size == 32) { "Malformed DPAPI key for alias '$alias'" }
            return decoded
        }
        val key = ByteArray(32).also(random::nextBytes)
        val blob = dpapi("P", key)
        OwnerOnlyFiles.ensureDir(dir)
        OwnerOnlyFiles.writeAtomically(file, blob)
        key
    }

    override fun delete(alias: String): Boolean = synchronized(lock) { fileFor(alias).delete() }

    private fun fileFor(alias: String): File {
        val safe = alias.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.dpapi")
    }

    private fun dpapi(mode: String, data: ByteArray): ByteArray {
        val res = runner.run(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", ENCODED_SCRIPT),
            "$mode:${Base64.getEncoder().encodeToString(data)}\n",
        )
        check(res.exitCode == 0) { "Windows DPAPI helper failed (exit ${res.exitCode})" }
        return try {
            Base64.getDecoder().decode(res.stdout.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Windows DPAPI helper returned malformed output", e)
        }
    }

    companion object {
        /** Reads "P:<base64>" (protect) or "U:<base64>" (unprotect) from stdin. No data inside. */
        internal const val SCRIPT =
            "\$ErrorActionPreference='Stop';" +
                "Add-Type -AssemblyName System.Security;" +
                "\$l=[Console]::In.ReadLine().Trim();" +
                "\$m=\$l.Substring(0,1);" +
                "\$b=[Convert]::FromBase64String(\$l.Substring(2));" +
                "\$s=[System.Security.Cryptography.DataProtectionScope]::CurrentUser;" +
                "if(\$m -eq 'P'){\$r=[System.Security.Cryptography.ProtectedData]::Protect(\$b,\$null,\$s)}" +
                "elseif(\$m -eq 'U'){\$r=[System.Security.Cryptography.ProtectedData]::Unprotect(\$b,\$null,\$s)}" +
                "else{exit 2};" +
                "[Console]::Out.Write([Convert]::ToBase64String(\$r))"

        internal val ENCODED_SCRIPT: String =
            Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
    }
}
