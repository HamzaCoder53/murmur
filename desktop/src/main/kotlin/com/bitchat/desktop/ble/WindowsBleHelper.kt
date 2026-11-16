package com.bitchat.desktop.ble

import com.bitchat.desktop.platform.BleTransport
import com.bitchat.desktop.platform.HostOs
import com.bitchat.desktop.platform.NoopBleTransport
import java.io.File

/**
 * Locates the bundled Windows WinRT BLE helper executable (`skein-ble-windows.exe`) and wraps
 * it in [StdioBleTransport], falling back to [NoopBleTransport] when not on Windows or absent.
 */
object WindowsBleHelper {
    const val HELPER_NAME = "skein-ble-windows.exe"

    fun findExecutable(): File? {
        System.getProperty("skein.bleHelper")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { resDir ->
                add(File(resDir, HELPER_NAME))
            }
            add(File("desktop/build/ble-windows/$HELPER_NAME"))
            add(File("build/ble-windows/$HELPER_NAME"))
        }
        return candidates.firstOrNull { it.isFile }
    }

    fun createTransport(): BleTransport {
        if (HostOs.current() != HostOs.WINDOWS) return NoopBleTransport
        val exe = findExecutable() ?: return NoopBleTransport
        return StdioBleTransport { StdioBleTransport.ProcessEndpoint(exe) }
    }
}
