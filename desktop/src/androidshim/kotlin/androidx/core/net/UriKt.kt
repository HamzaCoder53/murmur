package androidx.core.net

import android.net.Uri

/** Same as androidx.core.net.toUri (core-ktx). */
fun String.toUri(): Uri = Uri.parse(this)
