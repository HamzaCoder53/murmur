package com.bitchat.desktop.platform

import com.bitchat.desktop.shim.MasterKeyProvider
import com.bitchat.desktop.shim.ShimRuntime
import java.io.File

/**
 * Everything the desktop app needs from the host OS, behind interfaces so the shared core and
 * the UI never touch OS APIs directly. This stage ships no-op/fake implementations; later stages
 * plug in the macOS Swift BLE helper, the Windows WinRT BLE helper, Keychain/DPAPI, tray,
 * autostart and crash reporting without changing callers. See docs/desktop-architecture.md.
 */
class DesktopPlatform(
    val paths: AppPaths,
    val keyStore: SecureKeyStore,
    val ble: BleTransport = NoopBleTransport,
    val discovery: LocalDiscovery = NoopLocalDiscovery,
    val notifications: Notifier = NoopNotifier,
    val tray: TrayController = NoopTrayController,
    val autostart: AutostartController = NoopAutostartController,
    val crashReporter: CrashReporter = NoopCrashReporter,
) {
    /** Points the android.* shims at this platform's data directory and key store. */
    fun install(): DesktopPlatform {
        ShimRuntime.dataDir = paths.dataDir.apply { mkdirs() }
        ShimRuntime.masterKeyProvider = MasterKeyProvider { alias -> keyStore.getOrCreateKey(alias) }
        return this
    }

    companion object {
        const val ALLOW_INSECURE_KEYSTORE_PROP = "skein.allowInsecureKeyStore"

        /**
         * The platform for a normal run. Real OS integrations (Keychain/DPAPI, BLE helpers, tray)
         * replace the no-ops in later stages. Never persists plaintext keys by default: uses
         * [InMemoryKeyStore] unless `-Dskein.allowInsecureKeyStore=true` is explicitly set for dev.
         */
        fun forCurrentHost(dataDirOverride: File? = null): DesktopPlatform {
            val paths = AppPaths.forCurrentHost(dataDirOverride)
            val allowInsecure = System.getProperty(ALLOW_INSECURE_KEYSTORE_PROP)?.toBoolean() == true
            val os = HostOs.current()
            val keyStore: SecureKeyStore = when {
                allowInsecure -> {
                    android.util.Log.w(
                        "DesktopPlatform",
                        "Using insecure FileKeyStore under ${paths.secretsDir.name} (dev opt-in via -D$ALLOW_INSECURE_KEYSTORE_PROP=true)",
                    )
                    FileKeyStore(paths.secretsDir)
                }
                os == HostOs.MAC -> MacKeychainKeyStore()
                os == HostOs.WINDOWS -> WindowsDpapiKeyStore(paths.secretsDir)
                else -> {
                    android.util.Log.w(
                        "DesktopPlatform",
                        "No OS credential store on $os; using InMemoryKeyStore",
                    )
                    InMemoryKeyStore()
                }
            }
            val ble = when (os) {
                HostOs.MAC -> com.bitchat.desktop.ble.MacBleHelper.createTransport()
                HostOs.WINDOWS -> com.bitchat.desktop.ble.WindowsBleHelper.createTransport()
                HostOs.OTHER -> NoopBleTransport
            }
            val tray = AwtTrayController()
            val notifications = OsNotifier(tray = tray, hostOs = os)
            val autostart = OsAutostartController(hostOs = os)
            val crashReporter = com.bitchat.desktop.sentry.SentryCrashReporter(
                contextProvider = { android.content.Context(paths.dataDir) },
            ).apply {
                init(release = System.getProperty("skein.version") ?: "1.0.0")
            }
            return DesktopPlatform(
                paths = paths,
                keyStore = keyStore,
                ble = ble,
                notifications = notifications,
                tray = tray,
                autostart = autostart,
                crashReporter = crashReporter,
            )
        }
    }
}

enum class HostOs { MAC, WINDOWS, OTHER;
    companion object {
        fun current(): HostOs {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.startsWith("mac") || name.contains("darwin") -> MAC
                name.startsWith("windows") -> WINDOWS
                else -> OTHER
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// App-data paths
// ---------------------------------------------------------------------------------------------

/** Per-user storage locations. Nothing outside [dataDir] is ever written. */
data class AppPaths(val dataDir: File, val localDataDir: File = dataDir) {
    val secretsDir: File get() = File(localDataDir, "secrets")
    val logsDir: File get() = File(localDataDir, "logs")

    companion object {
        const val APP_DIR_NAME = "Skein"

        /**
         * macOS: ~/Library/Application Support/Skein.
         * Windows: %APPDATA%\Skein for general data, %LOCALAPPDATA%\Skein for secrets/logs
         * (avoiding roaming profile sync).
         * Other: XDG data. `-Dskein.dataDir=...` or [override] wins (tests, portable installs).
         */
        fun forCurrentHost(override: File? = null): AppPaths {
            override?.let { return AppPaths(it) }
            System.getProperty("skein.dataDir")?.takeIf { it.isNotBlank() }?.let { return AppPaths(File(it)) }
            val home = File(System.getProperty("user.home"))
            return when (HostOs.current()) {
                HostOs.MAC -> {
                    val dir = File(home, "Library/Application Support/$APP_DIR_NAME")
                    AppPaths(dir, dir)
                }
                HostOs.WINDOWS -> {
                    val roaming = File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: home.path, APP_DIR_NAME)
                    val local = File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: roaming.path, APP_DIR_NAME)
                    AppPaths(roaming, local)
                }
                HostOs.OTHER -> {
                    val dir = File(
                        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path,
                        APP_DIR_NAME.lowercase(),
                    )
                    AppPaths(dir, dir)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Secure key storage
// ---------------------------------------------------------------------------------------------

/**
 * Holds the 256-bit keys that seal on-disk state (identity, link secrets, conversation store).
 * Target implementations: macOS Keychain (generic password, this-device-only) and Windows DPAPI
 * (CryptProtectData, current user) via the native helpers.
 */
interface SecureKeyStore {
    fun getOrCreateKey(alias: String): ByteArray
    fun delete(alias: String): Boolean
}

// ---------------------------------------------------------------------------------------------
// Bluetooth LE (central + peripheral), implemented by per-OS helper processes later
// ---------------------------------------------------------------------------------------------

/** A BLE link to a nearby device; [linkId] is opaque and never a hardware address. */
data class BleLink(
    val linkId: String,
    val rssi: Int?,
    val mtu: Int = DEFAULT_MTU,
    val role: Role = Role.CENTRAL,
) {
    enum class Role { CENTRAL, PERIPHERAL }
    companion object {
        const val DEFAULT_MTU = 23
    }
}

enum class BleState { UNSUPPORTED, UNAUTHORIZED, POWERED_OFF, READY, HELPER_CRASHED }

interface BleTransport {
    /** Whether the OS radio supports both roles (scan/connect and advertise/GATT server). */
    val isAvailable: Boolean
    val state: BleState get() = if (isAvailable) BleState.READY else BleState.UNSUPPORTED
    fun start(listener: Listener)
    fun stop()
    /** Writes one already-encoded (and, if needed, fragmented) packet to a link. */
    fun send(linkId: String, frame: ByteArray): Boolean
    fun broadcast(frame: ByteArray, except: String? = null): Int

    interface Listener {
        fun onLinkUp(link: BleLink)
        fun onLinkUpdated(link: BleLink) = Unit
        fun onLinkDown(linkId: String)
        fun onFrame(linkId: String, frame: ByteArray)
        fun onStateChanged(state: BleState) = Unit
        fun onError(message: String) = Unit
    }
}

// ---------------------------------------------------------------------------------------------
// Local network discovery (mDNS / DNS-SD), for the LAN mesh and linked devices
// ---------------------------------------------------------------------------------------------

data class DiscoveredService(val name: String, val host: String, val port: Int)

interface LocalDiscovery {
    fun advertise(serviceType: String, name: String, port: Int): AutoCloseable
    fun browse(serviceType: String, onFound: (DiscoveredService) -> Unit, onLost: (String) -> Unit): AutoCloseable
}

// ---------------------------------------------------------------------------------------------
// Notifications, tray / menu bar, autostart, crash reporting
// ---------------------------------------------------------------------------------------------

interface Notifier {
    /** Shows a message notification. Content must already be a privacy-safe preview. */
    fun notify(id: String, title: String, body: String)
    fun cancel(id: String)
}

interface TrayController {
    val isSupported: Boolean
    fun show(tooltip: String, onOpen: () -> Unit, onQuit: () -> Unit)
    fun setUnreadCount(count: Int)
    fun hide()
}

interface AutostartController {
    val isSupported: Boolean
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean): Boolean
}

/** Opt-in crash reporting. Implementations must scrub peer IDs, keys, paths and message text. */
interface CrashReporter {
    val isEnabled: Boolean
    fun setEnabled(enabled: Boolean)
    fun init(release: String, environment: String = "production")
    fun addBreadcrumb(category: String, message: String)
    fun capture(throwable: Throwable, context: Map<String, String> = emptyMap())
    fun flush(timeoutMs: Long = 2_000)
    fun close()
}
