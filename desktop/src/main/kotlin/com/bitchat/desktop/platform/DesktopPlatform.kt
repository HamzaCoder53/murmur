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
        /** The platform for a normal run. Real OS integrations replace the no-ops in later stages. */
        fun forCurrentHost(dataDirOverride: File? = null): DesktopPlatform {
            val paths = AppPaths.forCurrentHost(dataDirOverride)
            return DesktopPlatform(paths = paths, keyStore = FileKeyStore(paths.secretsDir))
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
data class AppPaths(val dataDir: File) {
    val secretsDir: File get() = File(dataDir, "secrets")
    val logsDir: File get() = File(dataDir, "logs")

    companion object {
        const val APP_DIR_NAME = "Skein"

        /**
         * macOS: ~/Library/Application Support/Skein. Windows: %APPDATA%\Skein (roaming profile is
         * avoided for keys in a later stage by moving secrets to %LOCALAPPDATA%). Other: XDG data.
         * `-Dskein.dataDir=...` or [override] wins (tests, portable installs).
         */
        fun forCurrentHost(override: File? = null): AppPaths {
            override?.let { return AppPaths(it) }
            System.getProperty("skein.dataDir")?.takeIf { it.isNotBlank() }?.let { return AppPaths(File(it)) }
            val home = File(System.getProperty("user.home"))
            val dir = when (HostOs.current()) {
                HostOs.MAC -> File(home, "Library/Application Support/$APP_DIR_NAME")
                HostOs.WINDOWS -> File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: home.path, APP_DIR_NAME)
                HostOs.OTHER -> File(
                    System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path,
                    APP_DIR_NAME.lowercase(),
                )
            }
            return AppPaths(dir)
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
data class BleLink(val linkId: String, val rssi: Int?)

interface BleTransport {
    /** Whether the OS radio supports both roles (scan/connect and advertise/GATT server). */
    val isAvailable: Boolean
    fun start(listener: Listener)
    fun stop()
    /** Writes one already-encoded (and, if needed, fragmented) packet to a link. */
    fun send(linkId: String, frame: ByteArray): Boolean
    fun broadcast(frame: ByteArray, except: String? = null): Int

    interface Listener {
        fun onLinkUp(link: BleLink)
        fun onLinkDown(linkId: String)
        fun onFrame(linkId: String, frame: ByteArray)
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
    fun init(release: String)
    fun capture(throwable: Throwable)
}
