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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException

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

        // Different key: throws GeneralSecurityException and moves the original .enc aside so
        // it cannot be overwritten with a fresh empty store.
        EncryptedSharedPreferences.resetCacheForTests()
        ShimRuntime.masterKeyProvider = MasterKeyProvider { ByteArray(32) { 1 } }
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedSharedPreferences.create(
                ctx, "secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        assertFalse(file.exists())
        assertTrue(file.parentFile.listFiles().orEmpty().any { it.name.startsWith("secure.enc.corrupt-") })
        EncryptedSharedPreferences.resetCacheForTests()
    }

    @Test
    fun `Base64 decode matches Android lenient non-alphabet skipping and strict padding`() {
        assertArrayEquals("hi".toByteArray(), Base64.decode("a!G?k=", Base64.DEFAULT))
        assertArrayEquals("hi".toByteArray(), Base64.decode("aGk=\r\n", Base64.DEFAULT))
        assertThrows(IllegalArgumentException::class.java) { Base64.decode("QQ=", Base64.DEFAULT) }
        assertThrows(IllegalArgumentException::class.java) { Base64.decode("aGk=aGk=", Base64.DEFAULT) }
    }

    @Test
    fun `Uri decode replaces invalid percent escapes with U+FFFD like Android`() {
        val parsed = Uri.parse("skein://host?nick=%E&bad=%zz&plus=a+b")
        assertEquals("\uFFFDE", parsed.getQueryParameter("nick"))
        assertEquals("\uFFFDzz", parsed.getQueryParameter("bad"))
        assertEquals("a+b", parsed.getQueryParameter("plus"))
    }

    @Test
    fun `FileSharedPreferences throws ClassCastException on type mismatch and survives corrupt json`() {
        val ctx = devices.context("prefs-edge")
        val prefs = ctx.getSharedPreferences("edge", Context.MODE_PRIVATE)
        prefs.edit().putInt("num", 42).putFloat("nan", Float.NaN).commit()
        assertThrows(ClassCastException::class.java) { prefs.getString("num", null) }
        assertTrue(prefs.getFloat("nan", 0f).isNaN())

        val clearedKeys = mutableListOf<String?>()
        prefs.registerOnSharedPreferenceChangeListener { _, k -> clearedKeys += k }
        prefs.edit().clear().commit()
        assertTrue("num" in clearedKeys && "nan" in clearedKeys)

        Context.prefsCache.clear()
        File(ctx.prefsDir(), "edge.json").writeText("{not valid json")
        val recovered = ctx.getSharedPreferences("edge", Context.MODE_PRIVATE)
        assertTrue(recovered.all.isEmpty())
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
