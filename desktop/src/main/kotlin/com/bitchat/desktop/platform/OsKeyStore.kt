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
 *
 * The PowerShell script on the command line is a constant with no secret in it: the key (or the
 * blob to unprotect) goes to the child's stdin, so it never shows up in the process command line
 * or in process-creation / script-block logs. stderr is kept apart from stdout so PowerShell
 * warnings cannot corrupt the base64 result.
 */
class WindowsDpapiKeyStore(
    private val dir: File,
    private val runner: (command: List<String>, input: ByteArray) -> ByteArray = ::runStdinHelper,
) : SecureKeyStore {
    private val lock = Any()

    override fun getOrCreateKey(alias: String): ByteArray = synchronized(lock) {
        val file = fileFor(alias)
        if (file.exists()) {
            val plain = runner(powerShellCommand(UNPROTECT_SCRIPT), file.readBytes())
            if (plain.size != 32) {
                plain.fill(0)
                error("Malformed DPAPI key for alias '$alias'")
            }
            return plain
        }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val blob = runner(powerShellCommand(PROTECT_SCRIPT), key)
        check(blob.isNotEmpty()) { "Windows DPAPI returned an empty blob" }
        OwnerOnlyFiles.ensureDir(dir)
        OwnerOnlyFiles.writeAtomically(file, blob)
        key
    }

    override fun delete(alias: String): Boolean = synchronized(lock) { fileFor(alias).delete() }

    private fun fileFor(alias: String): File {
        val safe = alias.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.dpapi")
    }

    companion object {
        /** Constant scripts: no key material is ever interpolated into them. */
        const val PROTECT_SCRIPT =
            "\$p=[Convert]::FromBase64String([Console]::In.ReadLine());" +
                "\$b=[System.Security.Cryptography.ProtectedData]::Protect(\$p,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);" +
                "[Array]::Clear(\$p,0,\$p.Length);" +
                "[Console]::Out.WriteLine([Convert]::ToBase64String(\$b))"
        const val UNPROTECT_SCRIPT =
            "\$b=[Convert]::FromBase64String([Console]::In.ReadLine());" +
                "\$p=[System.Security.Cryptography.ProtectedData]::Unprotect(\$b,\$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);" +
                "[Console]::Out.WriteLine([Convert]::ToBase64String(\$p));" +
                "[Array]::Clear(\$p,0,\$p.Length)"

        fun powerShellCommand(script: String): List<String> {
            val full = "Add-Type -AssemblyName System.Security; $script"
            val encoded = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
            return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        }

        /**
         * Starts [command], writes [input] as one base64 line to its stdin and decodes the last
         * non-blank stdout line as base64. The base64 copy of [input] is zeroed after writing;
         * stderr is discarded instead of being merged into stdout.
         */
        fun runStdinHelper(command: List<String>, input: ByteArray): ByteArray {
            val proc = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val inputB64 = Base64.getEncoder().encode(input)
            try {
                proc.outputStream.use { out ->
                    out.write(inputB64)
                    out.write('\n'.code)
                    out.flush()
                }
            } finally {
                inputB64.fill(0)
            }
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            check(code == 0) { "Windows DPAPI helper failed (exit $code)" }
            val line = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                ?: error("Windows DPAPI helper printed no result")
            return Base64.getDecoder().decode(line)
        }
    }
}
