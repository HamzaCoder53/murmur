package com.bitchat.desktop.sentry

/**
 * Scrubs all personally identifying and mesh-sensitive data (peer IDs, hex/Base64 keys,
 * filesystem paths, nicknames, geohashes/coordinates, and message text) before any crash
 * report or breadcrumb is sent to Sentry.
 */
object SentryScrubber {
    private val hexKeyOrPeerRegex = Regex("""\b[0-9a-fA-F]{16,128}\b""")
    private val unixHomePathRegex = Regex("""/(Users|home)/[^/\s:]+""")
    private val winUserPathRegex = Regex("""[A-Za-z]:\\Users\\[^\\\s:]+""")
    private val ipv4Regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val coordRegex = Regex("""-?\d{1,3}\.\d{3,},\s*-?\d{1,3}\.\d{3,}""")
    private val sensitiveKeys = setOf(
        "message", "content", "nickname", "nick", "sender", "recipient",
        "peerid", "peer_id", "key", "secret", "token", "geohash", "location", "path",
    )

    fun scrubText(input: String): String {
        var s = input
        s = unixHomePathRegex.replace(s, "/<user>")
        s = winUserPathRegex.replace(s, """C:\<user>""")
        s = hexKeyOrPeerRegex.replace(s, "<hex-redacted>")
        s = ipv4Regex.replace(s, "<ip-redacted>")
        s = coordRegex.replace(s, "<loc-redacted>")
        return s
    }

    fun scrubContext(context: Map<String, String>): Map<String, String> =
        context.mapNotNull { (k, v) ->
            if (k.lowercase() in sensitiveKeys) {
                k to "<redacted>"
            } else {
                k to scrubText(v)
            }
        }.toMap()
}
