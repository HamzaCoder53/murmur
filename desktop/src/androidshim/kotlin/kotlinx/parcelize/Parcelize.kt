package kotlinx.parcelize

/** No-op on desktop; the Android build generates Parcelable code from these. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Parcelize

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class IgnoredOnParcel
