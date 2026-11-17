package com.bitchat.desktop

import android.content.Context
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.platform.AppPaths
import com.bitchat.desktop.platform.CrashWatchdog
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.HostOs
import com.bitchat.desktop.platform.OsAutostartController
import com.bitchat.desktop.platform.InMemoryKeyStore
import com.bitchat.desktop.shim.OwnerOnlyFiles
import com.bitchat.desktop.shim.ShimRuntime
import com.bitchat.desktop.ui.runDesktopApp
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * Skein desktop entry point.
 *
 *   (no args)   open the "Skein" window
 *   --smoke     headless self-check: create an identity in a throwaway directory, reload it,
 *               print the peer ID and exit 0 (non-zero on any failure). Never touches the real
 *               app-data directory or the OS key store.
 */
fun main(args: Array<String>) {
    if ("--smoke" in args) {
        exitProcess(runSmoke())
    }
    if ("--watchdog" in args) {
        // Only the packaged launcher can be re-run as a child; `java` alone has no classpath.
        val launcher = OsAutostartController.launcherPath()
        if (launcher != null) {
            exitProcess(CrashWatchdog.run(listOf(launcher) + args.filter { it != "--watchdog" }))
        }
        System.err.println("Skein watchdog: no packaged launcher (jpackage.app-path); running without it.")
    }
    val startHidden = "--autostart" in args
    val paths = AppPaths.forCurrentHost()
    // After a restart the previous process may still hold the lock for a moment.
    val lockWaitMs = if (DesktopRestart.RELAUNCHED_ARG in args) 15_000L else 0L
    val lockDeadline = System.currentTimeMillis() + lockWaitMs
    var instanceLock = acquireInstanceLock(paths.dataDir)
    while (instanceLock == null && System.currentTimeMillis() < lockDeadline) {
        Thread.sleep(250)
        instanceLock = acquireInstanceLock(paths.dataDir)
    }
    if (instanceLock == null) {
        System.err.println("Skein is already running for ${paths.dataDir.name}.")
        exitProcess(0)
    }
    val platform = DesktopPlatform.forCurrentHost().install()
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        platform.crashReporter.capture(e)
    }
    try {
        runDesktopApp(platform, startHidden = startHidden)
    } finally {
        try { platform.tray.hide() } catch (_: Exception) { }
        try { platform.crashReporter.flush() } catch (_: Exception) { }
        try { platform.crashReporter.close() } catch (_: Exception) { }
        try { instanceLock.close() } catch (_: Exception) { }
    }
}

internal fun acquireInstanceLock(dataDir: File): AutoCloseable? {
    OwnerOnlyFiles.ensureDir(dataDir)
    val lockFile = File(dataDir, "skein.lock")
    val channel = FileChannel.open(
        lockFile.toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    )
    OwnerOnlyFiles.restrict(lockFile)
    val lock: FileLock? = try {
        channel.tryLock()
    } catch (_: Exception) {
        null
    }
    if (lock == null) {
        channel.close()
        return null
    }
    return AutoCloseable {
        try { lock.release() } catch (_: Exception) { }
        try { channel.close() } catch (_: Exception) { }
    }
}

internal fun runSmoke(): Int = try {
    System.setProperty("java.awt.headless", "true")
    val dir = Files.createTempDirectory("skein-smoke").toFile()
    try {
        DesktopPlatform(paths = AppPaths(dir), keyStore = InMemoryKeyStore()).install()
        val created = DesktopIdentity.loadOrCreate(Context(dir))
        ShimRuntime.resetCachesForTests()
        val reloaded = DesktopIdentity.loadOrCreate(Context(dir))
        check(created.peerID == reloaded.peerID) { "identity did not survive a reload" }
        check(created.peerID.length == DesktopIdentity.PEER_ID_HEX_CHARS) { "bad peer ID length" }
        println("Skein desktop smoke OK")
        println("peerID=${created.peerID}")
        println("nickname=${created.nickname}")
        0
    } finally {
        ShimRuntime.resetCachesForTests()
        dir.deleteRecursively()
    }
} catch (e: Throwable) {
    System.err.println("Skein desktop smoke FAILED: ${e.javaClass.simpleName}: ${e.message}")
    1
}
