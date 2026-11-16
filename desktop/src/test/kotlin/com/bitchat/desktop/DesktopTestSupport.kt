package com.bitchat.desktop

import android.content.Context
import com.bitchat.desktop.platform.AppPaths
import com.bitchat.desktop.platform.DesktopPlatform
import com.bitchat.desktop.platform.InMemoryKeyStore
import com.bitchat.desktop.shim.ShimRuntime
import java.io.File
import java.nio.file.Files

/**
 * Isolated storage for one test: a throwaway data root and an in-memory key store. Each simulated
 * device gets its own Context directory, so identities never collide. Only synthetic data.
 */
class TestDevices : AutoCloseable {
    val root: File = Files.createTempDirectory("skein-desktop-test").toFile()
    val keyStore = InMemoryKeyStore()

    init {
        DesktopPlatform(paths = AppPaths(root), keyStore = keyStore).install()
    }

    fun context(name: String): Context = Context(File(root, name).apply { mkdirs() })

    override fun close() {
        ShimRuntime.resetCachesForTests()
        root.deleteRecursively()
    }
}
