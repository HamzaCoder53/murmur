package com.bitchat.desktop.shim

import java.io.File

/**
 * Process-wide configuration for the android.* shims. The desktop app sets these once at
 * startup from its DesktopPlatform; tests point [dataDir] at a temporary directory.
 */
object ShimRuntime {
    /** Root of all persisted state (preferences, caches). Must be set before first use. */
    @Volatile
    var dataDir: File? = null

    /** Supplies the 256-bit master key that encrypts EncryptedSharedPreferences files. */
    @Volatile
    var masterKeyProvider: MasterKeyProvider = UnsetMasterKeyProvider

    @Volatile
    var minLogLevel: Int = android.util.Log.WARN

    @Volatile
    var logSink: (level: Int, tag: String, msg: String, tr: Throwable?) -> Unit = { level, tag, msg, tr ->
        val letter = when (level) {
            android.util.Log.VERBOSE -> 'V'
            android.util.Log.DEBUG -> 'D'
            android.util.Log.INFO -> 'I'
            android.util.Log.WARN -> 'W'
            else -> 'E'
        }
        System.err.println("$letter/$tag: $msg" + (tr?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    fun requireDataDir(): File =
        checkNotNull(dataDir) { "ShimRuntime.dataDir is not set; call DesktopPlatform.install() first" }
            .apply { mkdirs() }
}

/**
 * Source of the key that protects encrypted preference files. Production implementations wrap
 * the OS credential store (macOS Keychain, Windows DPAPI); see DesktopPlatform.SecureKeyStore.
 */
fun interface MasterKeyProvider {
    /** Returns the same 32-byte key for [alias] on every call, creating it on first use. */
    fun getOrCreateKey(alias: String): ByteArray
}

private object UnsetMasterKeyProvider : MasterKeyProvider {
    override fun getOrCreateKey(alias: String): ByteArray =
        error("ShimRuntime.masterKeyProvider is not set; call DesktopPlatform.install() first")
}
