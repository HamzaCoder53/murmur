package android.content

import com.bitchat.desktop.shim.FileSharedPreferences
import com.bitchat.desktop.shim.ShimRuntime
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM stand-in for android.content.Context: only the application-scoped members the shared
 * core touches (applicationContext, getSharedPreferences, filesDir, cacheDir).
 *
 * Every Context resolves paths under [rootDir] (ShimRuntime.dataDir by default), so a test can
 * create an isolated Context per simulated device.
 */
open class Context(rootDir: File? = null) {
    private val explicitRoot = rootDir

    val rootDir: File
        get() = (explicitRoot ?: ShimRuntime.requireDataDir()).apply { mkdirs() }

    open val applicationContext: Context
        get() = this

    open val filesDir: File
        get() = File(rootDir, "files").apply { mkdirs() }

    open val cacheDir: File
        get() = File(rootDir, "cache").apply { mkdirs() }

    open val packageName: String
        get() = "xfuturehb.murmur"

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsCache.getOrPut(File(prefsDir(), "$name.json").canonicalPath) {
            FileSharedPreferences(File(prefsDir(), "$name.json"), codec = null)
        }

    fun prefsDir(): File = File(rootDir, "shared_prefs").apply { mkdirs() }

    companion object {
        const val MODE_PRIVATE = 0

        // One instance per backing file, like Android, so listeners and in-memory state agree.
        internal val prefsCache = ConcurrentHashMap<String, SharedPreferences>()
    }
}
