package com.bitchat.desktop.platform

import com.bitchat.desktop.shim.OwnerOnlyFiles
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object NoopBleTransport : BleTransport {
    override val isAvailable: Boolean = false
    override fun start(listener: BleTransport.Listener) = Unit
    override fun stop() = Unit
    override fun send(linkId: String, frame: ByteArray): Boolean = false
    override fun broadcast(frame: ByteArray, except: String?): Int = 0
}

object NoopLocalDiscovery : LocalDiscovery {
    override fun advertise(serviceType: String, name: String, port: Int): AutoCloseable = AutoCloseable { }
    override fun browse(serviceType: String, onFound: (DiscoveredService) -> Unit, onLost: (String) -> Unit): AutoCloseable =
        AutoCloseable { }
}

object NoopNotifier : Notifier {
    override fun notify(id: String, title: String, body: String) = Unit
    override fun cancel(id: String) = Unit
}

object NoopTrayController : TrayController {
    override val isSupported: Boolean = false
    override fun show(tooltip: String, onOpen: () -> Unit, onQuit: () -> Unit) = Unit
    override fun setUnreadCount(count: Int) = Unit
    override fun hide() = Unit
}

object NoopAutostartController : AutostartController {
    override val isSupported: Boolean = false
    override fun isEnabled(): Boolean = false
    override fun setEnabled(enabled: Boolean): Boolean = false
}

object NoopCrashReporter : CrashReporter {
    override val isEnabled: Boolean = false
    override fun init(release: String) = Unit
    override fun capture(throwable: Throwable) = Unit
}

/** Keys held in memory only: tests and the headless smoke run. */
class InMemoryKeyStore : SecureKeyStore {
    private val keys = ConcurrentHashMap<String, ByteArray>()
    override fun getOrCreateKey(alias: String): ByteArray =
        keys.getOrPut(alias) { ByteArray(32).also(SecureRandom()::nextBytes) }.copyOf()
    override fun delete(alias: String): Boolean = keys.remove(alias) != null
}

/**
 * PLACEHOLDER until the Keychain/DPAPI stage: random keys in owner-only files under the app-data
 * directory. This keeps the encrypted stores working across restarts, but anyone who can read the
 * user's files can read the key, so it is obfuscation, not protection. Do not ship a release with
 * this store.
 */
class FileKeyStore(private val dir: File) : SecureKeyStore {
    private val lock = Any()

    override fun getOrCreateKey(alias: String): ByteArray = synchronized(lock) {
        val file = fileFor(alias)
        if (file.isFile) {
            val bytes = file.readBytes()
            if (bytes.size == 32) return bytes
        }
        dir.mkdirs()
        OwnerOnlyFiles.restrict(dir)
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        file.writeBytes(key)
        OwnerOnlyFiles.restrict(file)
        key
    }

    override fun delete(alias: String): Boolean = synchronized(lock) { fileFor(alias).delete() }

    private fun fileFor(alias: String): File {
        val safe = alias.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.key")
    }
}
