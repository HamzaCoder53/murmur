package com.bitchat.desktop.ble

import com.bitchat.desktop.platform.BleTransport
import com.bitchat.desktop.platform.HostOs
import com.bitchat.desktop.platform.NoopBleTransport
import java.io.File

/**
 * Locates the bundled native BLE helper executable for the current OS and wraps it in
 * [StdioBleTransport], falling back to [NoopBleTransport] when no helper binary is present.
 */
object MacBleHelper {
    const val HELPER_NAME = "skein-ble-mac"

    fun findExecutable(): File? {
        System.getProperty("skein.bleHelper")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isFile && f.canExecute()) return f
        }
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { resDir ->
                add(File(resDir, HELPER_NAME))
            }
            add(File("desktop/build/ble-mac/$HELPER_NAME"))
            add(File("build/ble-mac/$HELPER_NAME"))
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    fun createTransport(): BleTransport {
        if (HostOs.current() != HostOs.MAC) return NoopBleTransport
        val exe = findExecutable() ?: return NoopBleTransport
        return StdioBleTransport { StdioBleTransport.ProcessEndpoint(exe) }
    }
}
