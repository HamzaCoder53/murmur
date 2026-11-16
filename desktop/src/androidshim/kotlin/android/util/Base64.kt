package android.util

/**
 * JVM stand-in for android.util.Base64 with Android's exact output format, so values written
 * by the phone (for example inside an identity bundle) decode identically here and vice versa.
 *
 * - DEFAULT encoding wraps at 76 characters with "\n" and ends with a trailing "\n".
 * - Decoding ignores whitespace and accepts missing padding, like Android's decoder.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private const val LINE_CHARS = 76

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String =
        encodeToString(input, 0, input.size, flags)

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String {
        val slice = input.copyOfRange(offset, offset + len)
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val raw = encoder.encodeToString(slice)
        if (flags and NO_WRAP != 0) return raw
        if (raw.isEmpty()) return raw
        val newline = if (flags and CRLF != 0) "\r\n" else "\n"
        val out = StringBuilder(raw.length + raw.length / LINE_CHARS * newline.length + newline.length)
        var i = 0
        while (i < raw.length) {
            val end = minOf(i + LINE_CHARS, raw.length)
            out.append(raw, i, end).append(newline)
            i = end
        }
        return out.toString()
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray =
        decode(str.toByteArray(Charsets.ISO_8859_1), flags)

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        val table = if (flags and URL_SAFE != 0) DECODE_WEBSAFE else DECODE_DEFAULT
        val out = java.io.ByteArrayOutputStream((input.size * 3) / 4 + 3)
        var state = 0
        var value = 0
        var p = 0
        val len = input.size
        while (p < len) {
            if (state == 0) {
                while (p + 4 <= len) {
                    val v = (table[input[p].toInt() and 0xFF] shl 18) or
                        (table[input[p + 1].toInt() and 0xFF] shl 12) or
                        (table[input[p + 2].toInt() and 0xFF] shl 6) or
                        table[input[p + 3].toInt() and 0xFF]
                    if (v < 0) break
                    out.write((v shr 16) and 0xFF)
                    out.write((v shr 8) and 0xFF)
                    out.write(v and 0xFF)
                    p += 4
                }
                if (p >= len) break
            }
            val d = table[input[p++].toInt() and 0xFF]
            when (state) {
                0 -> when {
                    d >= 0 -> { value = d; state = 1 }
                    d != SKIP -> throw IllegalArgumentException("bad base-64")
                }
                1 -> when {
                    d >= 0 -> { value = (value shl 6) or d; state = 2 }
                    d != SKIP -> throw IllegalArgumentException("bad base-64")
                }
                2 -> when {
                    d >= 0 -> { value = (value shl 6) or d; state = 3 }
                    d == EQUALS -> {
                        out.write(value shr 4)
                        state = 4
                    }
                    d != SKIP -> throw IllegalArgumentException("bad base-64")
                }
                3 -> when {
                    d >= 0 -> {
                        value = (value shl 6) or d
                        out.write((value shr 16) and 0xFF)
                        out.write((value shr 8) and 0xFF)
                        out.write(value and 0xFF)
                        state = 0
                    }
                    d == EQUALS -> {
                        out.write((value shr 10) and 0xFF)
                        out.write((value shr 2) and 0xFF)
                        state = 5
                    }
                    d != SKIP -> throw IllegalArgumentException("bad base-64")
                }
                4 -> when {
                    d == EQUALS -> state = 5
                    d != SKIP -> throw IllegalArgumentException("bad base-64")
                }
                5 -> if (d != SKIP) throw IllegalArgumentException("bad base-64")
            }
        }
        when (state) {
            0, 5 -> Unit
            1 -> throw IllegalArgumentException("bad base-64")
            2 -> out.write(value shr 4)
            3 -> {
                out.write((value shr 10) and 0xFF)
                out.write((value shr 2) and 0xFF)
            }
            4 -> throw IllegalArgumentException("bad base-64")
        }
        return out.toByteArray()
    }

    private const val SKIP = -1
    private const val EQUALS = -2

    private val DECODE_DEFAULT = IntArray(256) { SKIP }.apply {
        for (i in 'A'.code..'Z'.code) this[i] = i - 'A'.code
        for (i in 'a'.code..'z'.code) this[i] = i - 'a'.code + 26
        for (i in '0'.code..'9'.code) this[i] = i - '0'.code + 52
        this['+'.code] = 62
        this['/'.code] = 63
        this['='.code] = EQUALS
    }

    private val DECODE_WEBSAFE = IntArray(256) { SKIP }.apply {
        for (i in 'A'.code..'Z'.code) this[i] = i - 'A'.code
        for (i in 'a'.code..'z'.code) this[i] = i - 'a'.code + 26
        for (i in '0'.code..'9'.code) this[i] = i - '0'.code + 52
        this['-'.code] = 62
        this['_'.code] = 63
        this['='.code] = EQUALS
    }
}
