package com.bitchat.desktop.platform

import java.io.File
import kotlin.concurrent.thread

data class ProcessResult(val exitCode: Int, val stdout: String)

/**
 * Runs an OS helper command. Anything secret must go through [stdin], never through [command]:
 * other processes of the same user, process auditing and script-block logging can read argv.
 * Tests substitute a fake to cover the failure paths of the key stores and autostart.
 */
fun interface ProcessRunner {
    fun run(command: List<String>, stdin: String?): ProcessResult
}

object SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, stdin: String?): ProcessResult {
        val proc = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { if (stdin == null) redirectInput(ProcessBuilder.Redirect.from(nullDevice())) }
            .start()
        // Feed stdin on its own thread so a child that writes before it reads cannot deadlock us.
        val writer = stdin?.let { input ->
            thread(isDaemon = true, name = "skein-process-stdin") {
                try {
                    proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
                } catch (_: Exception) {
                }
            }
        }
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val code = proc.waitFor()
        writer?.join(1_000)
        return ProcessResult(code, out)
    }

    private fun nullDevice(): File =
        if (HostOs.current() == HostOs.WINDOWS) File("NUL") else File("/dev/null")
}
