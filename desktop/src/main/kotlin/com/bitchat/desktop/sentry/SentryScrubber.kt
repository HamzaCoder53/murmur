package com.bitchat.desktop.sentry

/**
 * Best-effort, pattern-based scrubbing for breadcrumbs and context values: long hex runs (keys,
 * full peer IDs), npub/nsec, home-directory paths, IPv4 addresses and coordinates, plus whole
 * values under sensitive context keys. Patterns cannot recognise free text such as nicknames or
 * message content, so callers must never put those in breadcrumbs, and [SentryCrashReporter]
 * never sends exception messages at all.
 */
object SentryScrubber {
    private val hexKeyOrPeerRegex = Regex("""\b[0-9a-fA-F]{16,128}\b""")
    private val bech32NostrRegex = Regex("""\b(?:npub|nsec)1[023456789acdefghjklmnpqrstuvwxyz]{20,}\b""")
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
        s = bech32NostrRegex.replace(s, "<nostr-redacted>")
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
