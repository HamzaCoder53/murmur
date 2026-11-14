# Gson reflection targets in the shared bitchat sources (persisted state payloads).
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.services.SeenMessageStore$* { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin metadata needed by reflection-based serialization.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Tink references JSR-305 annotations not present on Android.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Internet private messages: Nostr events, filters and requests go through Gson by field name
# (same keep rule the phone app uses).
-keep class com.bitchat.android.nostr.** { *; }
