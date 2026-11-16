package com.bitchat.desktop.shim

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.desktop.TestDevices
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidShimTest {
    private val devices = TestDevices()

    @After
    fun tearDown() = devices.close()

    @Test
    fun `Base64 DEFAULT matches Android output - 76 char lines and trailing newline`() {
        val data = ByteArray(100) { it.toByte() }
        val encoded = Base64.encodeToString(data, Base64.DEFAULT)
        val lines = encoded.split('\n')
        assertEquals(76, lines[0].length)
        assertTrue(encoded.endsWith("\n"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(data), encoded.replace("\n", ""))
        assertArrayEquals(data, Base64.decode(encoded, Base64.DEFAULT))
        assertEquals("", Base64.encodeToString(ByteArray(0), Base64.DEFAULT))
    }

    @Test
    fun `Base64 flags NO_WRAP URL_SAFE and NO_PADDING`() {
        val data = byteArrayOf(-5, -1, -2, 1)
        assertEquals("+//+AQ==", Base64.encodeToString(data, Base64.NO_WRAP))
        assertEquals("-__-AQ", Base64.encodeToString(data, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING))
        assertArrayEquals(data, Base64.decode("-__-AQ", Base64.URL_SAFE))
        assertArrayEquals(data, Base64.decode("+//+AQ", Base64.DEFAULT))
    }

    @Test
    fun `preferences keep value types across reloads`() {
        val ctx = devices.context("prefs")
        val prefs = ctx.getSharedPreferences("synthetic", Context.MODE_PRIVATE)
        assertTrue(
            prefs.edit().putInt("i", 7).putLong("l", 1L shl 40).putBoolean("b", true)
                .putString("s", "x").putStringSet("ss", setOf("a", "b")).putFloat("f", 1.5f).commit()
        )
        Context.prefsCache.clear()
        val reloaded = Context(File(devices.root, "prefs")).getSharedPreferences("synthetic", Context.MODE_PRIVATE)
        assertEquals(7, reloaded.getInt("i", 0))
        assertEquals(1L shl 40, reloaded.getLong("l", 0))
        assertTrue(reloaded.getBoolean("b", false))
        assertEquals("x", reloaded.getString("s", null))
        assertEquals(setOf("a", "b"), reloaded.getStringSet("ss", null))
        assertEquals(1.5f, reloaded.getFloat("f", 0f))
        assertEquals(6, reloaded.all.size)
        reloaded.edit().remove("i").apply()
        assertFalse(reloaded.contains("i"))
    }

    @Test
    fun `encrypted preferences are unreadable on disk and fail closed with the wrong key`() {
        val ctx = devices.context("enc")
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            ctx, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.edit().putString("synthetic_secret_name", "synthetic-secret-value").commit()
        val file = File(devices.root, "enc/shared_prefs/secure.enc")
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("synthetic_secret_name") || raw.contains("synthetic-secret-value"))

        // Same key: readable after a fresh load.
        EncryptedSharedPreferences.resetCacheForTests()
        val again = EncryptedSharedPreferences.create(
            ctx, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        assertEquals("synthetic-secret-value", again.getString("synthetic_secret_name", null))

        // Different key: nothing is returned (authentication fails, store starts empty).
        EncryptedSharedPreferences.resetCacheForTests()
        ShimRuntime.masterKeyProvider = MasterKeyProvider { ByteArray(32) { 1 } }
        val wrong = EncryptedSharedPreferences.create(
            ctx, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        assertNull(wrong.getString("synthetic_secret_name", null))
        EncryptedSharedPreferences.resetCacheForTests()
    }

    @Test
    fun `Uri builds and parses query parameters like Android`() {
        val uri = Uri.Builder().scheme("skein-link").authority("v1")
            .appendQueryParameter("d", "abc")
            .appendQueryParameter("s", "a b+c/=")
            .build()
        assertEquals("skein-link://v1?d=abc&s=a%20b%2Bc%2F%3D", uri.toString())
        val parsed = Uri.parse(uri.toString())
        assertEquals("skein-link", parsed.scheme)
        assertEquals("v1", parsed.authority)
        assertEquals("a b+c/=", parsed.getQueryParameter("s"))
        assertNull(parsed.getQueryParameter("missing"))
        assertEquals("example.org", Uri.parse("https://example.org/path?x=1").host)
    }
}
