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
    fun decode(str: String, flags: Int): ByteArray {
        val compact = buildString(str.length) {
            for (c in str) if (!c.isWhitespace()) append(c)
        }.trimEnd('=')
        val decoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()
        // java.util.Base64 accepts unpadded input; Android also accepts both alphabets loosely,
        // but the shared code always decodes with the flags it encoded with.
        return decoder.decode(compact)
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input, Charsets.US_ASCII), flags)
}
