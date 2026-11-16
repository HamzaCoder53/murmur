package com.bitchat.desktop.ble

import com.bitchat.desktop.platform.BleLink
import com.bitchat.desktop.platform.BleState
import com.bitchat.desktop.platform.BleTransport
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * [BleTransport] implementation that communicates with a native OS BLE helper process
 * (Swift CoreBluetooth on macOS, .NET WinRT on Windows) over newline-delimited JSON on stdio.
 */
class StdioBleTransport(
    private val endpointFactory: () -> Endpoint,
) : BleTransport {

    /** Abstraction over a spawned process or in-memory stream pair (for tests). */
    interface Endpoint : AutoCloseable {
        val stdout: InputStream
        val stdin: OutputStream
        fun waitForExit(): Int
    }

    class ProcessEndpoint(private val executable: File) : Endpoint {
        private val process: Process = ProcessBuilder(executable.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        override val stdout: InputStream get() = process.inputStream
        override val stdin: OutputStream get() = process.outputStream
        override fun waitForExit(): Int = process.waitFor()
        override fun close() {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private val running = AtomicBoolean(false)
    private val links = ConcurrentHashMap<String, BleLink>()
    private val writeLock = Any()

    @Volatile
    private var currentState: BleState = BleState.UNSUPPORTED
    @Volatile
    private var endpoint: Endpoint? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var listener: BleTransport.Listener? = null
    private var readerThread: Thread? = null

    override val isAvailable: Boolean
        get() = currentState == BleState.READY

    override val state: BleState
        get() = currentState

    override fun start(listener: BleTransport.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        val ep = try {
            endpointFactory()
        } catch (e: Exception) {
            running.set(false)
            updateState(BleState.HELPER_CRASHED)
            listener.onError("Failed to start BLE helper: ${e.message}")
            return
        }
        endpoint = ep
        writer = BufferedWriter(OutputStreamWriter(ep.stdin, Charsets.UTF_8))

        readerThread = thread(isDaemon = true, name = "skein-ble-stdio-reader") {
            readLoop(ep, listener)
        }
        writeCommand(JSONObject().put("op", "start"))
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        writeCommand(JSONObject().put("op", "stop"))
        try { writer?.close() } catch (_: Exception) { }
        writer = null
        try { endpoint?.close() } catch (_: Exception) { }
        endpoint = null
        val activeIds = links.keys.toList()
        links.clear()
        val l = listener
        for (id in activeIds) l?.onLinkDown(id)
        listener = null
    }

    override fun send(linkId: String, frame: ByteArray): Boolean {
        if (!running.get() || !links.containsKey(linkId)) return false
        val b64 = Base64.getEncoder().encodeToString(frame)
        return writeCommand(
            JSONObject()
                .put("op", "send")
                .put("link", linkId)
                .put("b64", b64),
        )
    }

    override fun broadcast(frame: ByteArray, except: String?): Int {
        if (!running.get()) return 0
        val targets = links.keys.filter { it != except }
        if (targets.isEmpty()) return 0
        val b64 = Base64.getEncoder().encodeToString(frame)
        var sent = 0
        for (id in targets) {
            if (writeCommand(JSONObject().put("op", "send").put("link", id).put("b64", b64))) {
                sent++
            }
        }
        return sent
    }

    internal fun activeLinks(): Map<String, BleLink> = links.toMap()

    private fun readLoop(ep: Endpoint, l: BleTransport.Listener) {
        try {
            BufferedReader(InputStreamReader(ep.stdout, Charsets.UTF_8)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleEventLine(line, l)
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                l.onError("BLE helper read error: ${e.message}")
            }
        } finally {
            if (running.compareAndSet(true, false)) {
                val code = try { ep.waitForExit() } catch (_: Exception) { -1 }
                val activeIds = links.keys.toList()
                links.clear()
                for (id in activeIds) l.onLinkDown(id)
                l.onError("BLE helper exited unexpectedly (code $code)")
                updateState(BleState.HELPER_CRASHED)
                try { ep.close() } catch (_: Exception) { }
                endpoint = null
                writer = null
            }
        }
    }

    private fun handleEventLine(line: String, l: BleTransport.Listener) {
        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        when (obj.optString("ev")) {
            "state" -> {
                val parsed = parseState(obj.optString("state"))
                updateState(parsed)
            }
            "linkUp" -> {
                val id = obj.optString("link").takeIf { it.isNotEmpty() } ?: return
                val rssi = if (obj.has("rssi") && !obj.isNull("rssi")) obj.optInt("rssi") else null
                val mtu = obj.optInt("mtu", BleLink.DEFAULT_MTU).coerceAtLeast(BleLink.DEFAULT_MTU)
                val role = if (obj.optString("role").equals("peripheral", ignoreCase = true)) {
                    BleLink.Role.PERIPHERAL
                } else {
                    BleLink.Role.CENTRAL
                }
                val link = BleLink(linkId = id, rssi = rssi, mtu = mtu, role = role)
                links[id] = link
                l.onLinkUp(link)
            }
            "linkUpdated" -> {
                val id = obj.optString("link").takeIf { it.isNotEmpty() } ?: return
                val existing = links[id] ?: return
                val rssi = if (obj.has("rssi") && !obj.isNull("rssi")) obj.optInt("rssi") else existing.rssi
                val mtu = obj.optInt("mtu", existing.mtu).coerceAtLeast(BleLink.DEFAULT_MTU)
                val updated = existing.copy(rssi = rssi, mtu = mtu)
                links[id] = updated
                l.onLinkUpdated(updated)
            }
            "linkDown" -> {
                val id = obj.optString("link").takeIf { it.isNotEmpty() } ?: return
                if (links.remove(id) != null) {
                    l.onLinkDown(id)
                }
            }
            "frame" -> {
                val id = obj.optString("link").takeIf { it.isNotEmpty() } ?: return
                val b64 = obj.optString("b64").takeIf { it.isNotEmpty() } ?: return
                val bytes = try {
                    Base64.getDecoder().decode(b64)
                } catch (_: IllegalArgumentException) {
                    return
                }
                l.onFrame(id, bytes)
            }
            "error" -> {
                val msg = obj.optString("message", "Unknown BLE helper error")
                l.onError(msg)
            }
        }
    }

    private fun updateState(newState: BleState) {
        if (currentState != newState) {
            currentState = newState
            listener?.onStateChanged(newState)
        }
    }

    private fun parseState(raw: String): BleState = when (raw.lowercase()) {
        "ready", "poweredon" -> BleState.READY
        "poweredoff" -> BleState.POWERED_OFF
        "unauthorized" -> BleState.UNAUTHORIZED
        "helpercrashed", "helper_crashed" -> BleState.HELPER_CRASHED
        else -> BleState.UNSUPPORTED
    }

    private fun writeCommand(obj: JSONObject): Boolean = synchronized(writeLock) {
        val w = writer ?: return false
        return try {
            w.write(obj.toString())
            w.newLine()
            w.flush()
            true
        } catch (_: Exception) {
            false
        }
    }
}
