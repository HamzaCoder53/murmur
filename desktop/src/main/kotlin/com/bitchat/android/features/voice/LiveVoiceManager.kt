package com.bitchat.android.features.voice

import android.content.Context
import com.bitchat.android.model.BitchatMessage

enum class LiveVoiceScope { DIRECT_MESSAGE, PUBLIC_MESH }

/**
 * Desktop variant (same FQN) of the phone's push-to-talk receiver. The phone version decodes
 * Opus/AAC with android.media; desktop has no audio pipeline yet, so live frames are validated
 * and dropped exactly as the phone does when live voice is switched off in settings, and the
 * finished voice note still arrives as a normal audio message.
 */
class LiveVoiceManager private constructor() {
    companion object {
        @Volatile
        private var instance: LiveVoiceManager? = null

        fun getInstance(@Suppress("UNUSED_PARAMETER") context: Context): LiveVoiceManager =
            instance ?: synchronized(this) { instance ?: LiveVoiceManager().also { instance = it } }
    }

    /** Same result as the phone with live voice disabled: malformed frames false, valid ones consumed. */
    @Suppress("UNUSED_PARAMETER")
    fun handleFrame(
        peerID: String,
        nickname: String,
        scope: LiveVoiceScope,
        payload: ByteArray,
        timestampMs: Long,
    ): Boolean = VoiceBurstPacket.decode(payload) != null

    /** Nothing was assembled live, so the voice note is shown as a regular message. */
    fun absorbFinalizedVoiceNote(@Suppress("UNUSED_PARAMETER") message: BitchatMessage): Boolean = false
}
