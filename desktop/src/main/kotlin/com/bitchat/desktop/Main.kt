package com.bitchat.desktop

import android.content.Context
import com.bitchat.desktop.core.DesktopIdentity
import com.bitchat.desktop.platform.AppPaths
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.InMemoryKeyStore
import com.bitchat.desktop.ui.runDesktopApp
import java.nio.file.Files
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
    val platform = DesktopPlatform.forCurrentHost().install()
    runDesktopApp(platform)
}

internal fun runSmoke(): Int = try {
    System.setProperty("java.awt.headless", "true")
    val dir = Files.createTempDirectory("skein-smoke").toFile()
    try {
        DesktopPlatform(paths = AppPaths(dir), keyStore = InMemoryKeyStore()).install()
        val created = DesktopIdentity.loadOrCreate(Context(dir))
        val reloaded = DesktopIdentity.loadOrCreate(Context(dir))
        check(created.peerID == reloaded.peerID) { "identity did not survive a reload" }
        check(created.peerID.length == DesktopIdentity.PEER_ID_HEX_CHARS) { "bad peer ID length" }
        println("Skein desktop smoke OK")
        println("peerID=${created.peerID}")
        println("nickname=${created.nickname}")
        0
    } finally {
        dir.deleteRecursively()
    }
} catch (e: Throwable) {
    System.err.println("Skein desktop smoke FAILED: ${e.javaClass.simpleName}: ${e.message}")
    1
}
