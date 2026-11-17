package com.bitchat.desktop.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * Lightweight JDK `javax.sound.sampled` WAV voice-note recorder for desktop.
 * Uses only `java.desktop` (no native codecs or new Gradle dependencies).
 */
class DesktopAudioRecorder {
    private val recording = AtomicBoolean(false)
    private var line: TargetDataLine? = null
    private var buffer: ByteArrayOutputStream? = null
    private var worker: Thread? = null

    val isRecording: Boolean get() = recording.get()

    fun start(): Boolean {
        if (!recording.compareAndSet(false, true)) return false
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            val mic = AudioSystem.getLine(info) as TargetDataLine
            mic.open(FORMAT)
            mic.start()
            val out = ByteArrayOutputStream()
            line = mic
            buffer = out
            worker = thread(isDaemon = true, name = "skein-voice-rec") {
                val chunk = ByteArray(4096)
                while (recording.get()) {
                    val n = mic.read(chunk, 0, chunk.size)
                    if (n > 0) out.write(chunk, 0, n)
                }
            }
            true
        } catch (_: Exception) {
            recording.set(false)
            false
        }
    }

    fun stopToFile(cacheDir: File): File? {
        if (!recording.compareAndSet(true, false)) return null
        try { line?.stop() } catch (_: Exception) { }
        try { line?.close() } catch (_: Exception) { }
        try { worker?.join(1_000) } catch (_: Exception) { }
        val pcm = buffer?.toByteArray() ?: return null
        line = null
        buffer = null
        worker = null
        if (pcm.isEmpty()) return null

        val outDir = File(cacheDir, "voice_out").apply { mkdirs() }
        val outFile = File(outDir, "voice_${System.currentTimeMillis()}.wav")
        val ais = AudioInputStream(
            ByteArrayInputStream(pcm),
            FORMAT,
            pcm.size.toLong() / FORMAT.frameSize,
        )
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outFile)
        return outFile
    }

    companion object {
        private val FORMAT = AudioFormat(16_000f, 16, 1, true, false)
    }
}
