package androidx.security.crypto

import android.content.Context

/**
 * Stand-in for androidx.security.crypto.MasterKey. It only names a key; the key bytes come from
 * ShimRuntime.masterKeyProvider (the OS credential store on desktop) when a store is opened.
 */
class MasterKey private constructor(val alias: String) {
    enum class KeyScheme { AES256_GCM }

    class Builder(
        @Suppress("UNUSED_PARAMETER") context: Context,
        private val alias: String = DEFAULT_MASTER_KEY_ALIAS,
    ) {
        fun setKeyScheme(@Suppress("UNUSED_PARAMETER") scheme: KeyScheme): Builder = this
        fun setUserAuthenticationRequired(@Suppress("UNUSED_PARAMETER") required: Boolean): Builder = this
        fun build(): MasterKey = MasterKey(alias)
    }

    companion object {
        const val DEFAULT_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }
}
