package android.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Minimal hierarchical-URI stand-in for android.net.Uri: scheme, authority, path and query
 * parameters, which is all LinkQr (`skeinlink://...?d=&s=&h=&p=`) needs. Encoding follows
 * Android's Uri.encode (only unreserved characters stay literal); getQueryParameter decodes
 * percent escapes without turning '+' into a space, like Android.
 */
class Uri private constructor(
    val scheme: String?,
    private val encodedAuthority: String?,
    val path: String?,
    private val encodedQuery: String?,
) {
    val authority: String? get() = encodedAuthority?.let(::decode)
    val host: String? get() = authority?.substringAfterLast('@')?.substringBefore(':')

    val query: String? get() = encodedQuery?.let(::decode)

    fun getQueryParameter(key: String): String? {
        val q = encodedQuery ?: return null
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            if (name == key) return if (eq >= 0) decode(pair.substring(eq + 1)) else ""
        }
        return null
    }

    fun getQueryParameterNames(): Set<String> =
        encodedQuery?.split('&')?.filter { it.isNotEmpty() }
            ?.map { decode(it.substringBefore('=')) }?.toCollection(LinkedHashSet()) ?: emptySet()

    override fun toString(): String = buildString {
        if (scheme != null) append(scheme).append(':')
        if (encodedAuthority != null) append("//").append(encodedAuthority)
        if (path != null) append(path)
        if (encodedQuery != null) append('?').append(encodedQuery)
    }

    override fun equals(other: Any?): Boolean = other is Uri && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String? = null
        private val query = StringBuilder()
        private var hasQuery = false

        fun scheme(scheme: String?): Builder = apply { this.scheme = scheme }
        fun authority(authority: String?): Builder = apply { this.authority = authority?.let { encode(it, ":@") } }
        fun path(path: String?): Builder = apply { this.path = path?.let { encode(it, "/") } }
        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            if (hasQuery) query.append('&')
            query.append(encode(key)).append('=').append(encode(value ?: ""))
            hasQuery = true
        }
        fun build(): Uri = Uri(scheme, authority, path, if (hasQuery) query.toString() else null)
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri {
            val s = uriString
            val schemeEnd = s.indexOf(':')
            val firstDelim = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val scheme = if (schemeEnd > 0 && (firstDelim == -1 || schemeEnd < firstDelim)) s.substring(0, schemeEnd) else null
            var rest = if (scheme != null) s.substring(schemeEnd + 1) else s
            rest = rest.substringBefore('#')
            var authority: String? = null
            if (rest.startsWith("//")) {
                val afterSlashes = rest.substring(2)
                val end = afterSlashes.indexOfFirst { it == '/' || it == '?' }
                authority = if (end == -1) afterSlashes else afterSlashes.substring(0, end)
                rest = if (end == -1) "" else afterSlashes.substring(end)
            }
            val q = rest.indexOf('?')
            val path = (if (q == -1) rest else rest.substring(0, q)).ifEmpty { null }
            val query = if (q == -1) null else rest.substring(q + 1)
            return Uri(scheme, authority, path, query)
        }

        private const val UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*"

        @JvmStatic
        fun encode(s: String): String = encode(s, "")

        @JvmStatic
        fun encode(s: String, allow: String): String {
            val out = StringBuilder(s.length)
            for (b in s.toByteArray(StandardCharsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (b >= 0 && (c in UNRESERVED || c in allow)) {
                    out.append(c)
                } else {
                    out.append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
            return out.toString()
        }

        @JvmStatic
        fun decode(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder(s.length)
            val bytes = ByteArrayOutputStream()
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            fun flushBytes() {
                if (bytes.size() > 0) {
                    out.append(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())))
                    decoder.reset()
                    bytes.reset()
                }
            }
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    val hi = if (i + 1 < s.length) s[i + 1].digitToIntOrNull(16) else null
                    val lo = if (i + 2 < s.length) s[i + 2].digitToIntOrNull(16) else null
                    if (hi != null && lo != null) {
                        bytes.write((hi shl 4) or lo)
                        i += 3
                    } else {
                        flushBytes()
                        out.append('\uFFFD')
                        i += 1
                    }
                } else {
                    flushBytes()
                    out.append(c)
                    i += 1
                }
            }
            flushBytes()
            return out.toString()
        }
    }
}
