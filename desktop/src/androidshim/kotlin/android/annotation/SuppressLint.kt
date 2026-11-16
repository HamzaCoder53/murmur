package android.annotation

/** Lint-only annotation; meaningless on desktop. */
@Retention(AnnotationRetention.SOURCE)
annotation class SuppressLint(vararg val value: String)
