package androidx.core.content

import android.content.SharedPreferences

/** Same as androidx.core.content.edit (core-ktx). */
inline fun SharedPreferences.edit(
    commit: Boolean = false,
    action: SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
