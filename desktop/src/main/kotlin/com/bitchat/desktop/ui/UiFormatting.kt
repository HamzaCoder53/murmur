package com.bitchat.desktop.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** Same grouping as the phone's verification sheet: blocks of 4, a line break every 16 chars. */
fun formatFingerprint(fingerprint: String): String {
    val upper = fingerprint.uppercase()
    val sb = StringBuilder()
    upper.forEachIndexed { index, c ->
        if (index > 0 && index % 4 == 0) {
            if (index % 16 == 0) sb.append('\n') else sb.append(' ')
        }
        sb.append(c)
    }
    return sb.toString()
}

/** "@name" mentions that refer to a known nickname (same rule as the phone's MessageManager). */
fun parseMentions(text: String, knownNicknames: Set<String>): List<String> =
    MENTION_REGEX.findAll(text)
        .map { it.groupValues[1] }
        .filter { it in knownNicknames }
        .distinct()
        .toList()

fun mentionsNickname(text: String, nickname: String): Boolean =
    MENTION_REGEX.findAll(text).any { it.groupValues[1].equals(nickname, ignoreCase = true) }

/** The "@prefix" being typed at the end of [draft], or null. */
fun pendingMentionPrefix(draft: String): String? {
    val token = draft.substringAfterLast(' ').substringAfterLast('\n')
    if (!token.startsWith("@")) return null
    val prefix = token.drop(1)
    return if (prefix.all { it.isLetterOrDigit() || it == '_' }) prefix else null
}

fun mentionSuggestions(draft: String, nicknames: Collection<String>, limit: Int = 5): List<String> {
    val prefix = pendingMentionPrefix(draft) ?: return emptyList()
    return nicknames
        .filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }
        .distinct()
        .sortedBy { it.lowercase() }
        .take(limit)
}

fun applyMention(draft: String, nickname: String): String {
    pendingMentionPrefix(draft) ?: return draft
    val cut = draft.lastIndexOf('@')
    return draft.substring(0, cut) + "@" + nickname + " "
}

/** "14:05" for today, otherwise "3 Nov 14:05" (localized month name). */
fun formatTimestamp(date: Date, now: Date, zone: ZoneId, language: AppLanguage): String {
    val then = Instant.ofEpochMilli(date.time).atZone(zone)
    val today = Instant.ofEpochMilli(now.time).atZone(zone).toLocalDate()
    val pattern = if (then.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"
    return DateTimeFormatter.ofPattern(pattern, language.locale).format(then)
}

fun deliveryLabel(state: DeliveryState, strings: UiStrings): String = when (state) {
    DeliveryState.SENDING -> strings.statusSending
    DeliveryState.SENT -> strings.statusSent
    DeliveryState.DELIVERED -> strings.statusDelivered
    DeliveryState.READ -> strings.statusRead
    DeliveryState.FAILED -> strings.statusFailed
}

fun sessionLabel(state: SessionState, strings: UiStrings): String = when (state) {
    SessionState.NONE -> strings.sessionNone
    SessionState.HANDSHAKING -> strings.sessionHandshaking
    SessionState.ESTABLISHED -> strings.sessionEstablished
}

/** Geohash channel names use the base32 geohash alphabet, 1 to 12 characters. */
fun normalizeGeohash(input: String): String? {
    val g = input.trim().lowercase().removePrefix("#")
    if (g.isEmpty() || g.length > 12) return null
    return if (g.all { it in GEOHASH_ALPHABET }) g else null
}

fun validNickname(input: String): String? {
    val t = input.trim()
    return if (t.isNotEmpty() && t.length <= MAX_NICKNAME_CHARS) t else null
}

const val MAX_NICKNAME_CHARS = 32
private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
private val MENTION_REGEX = "@([\\p{L}\\p{N}_]+)".toRegex()
