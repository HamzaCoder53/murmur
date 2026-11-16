package com.bitchat.watch.ui

import com.bitchat.watch.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/**
 * Nickname entry, used both for first-run onboarding and for renaming later. The IME's
 * Done action only closes the keyboard so the user can review the name; the edge button is
 * the single commit path.
 *
 * [autoFocus] opens the keyboard immediately. Renaming asks for it; onboarding does not, so a
 * new user first sees what the field is for and can join with the suggested name in one tap.
 */
@Composable
fun NicknameSetupScreen(
    initialNickname: String,
    title: String = stringResource(R.string.app_name),
    subtitle: String = stringResource(R.string.nickname_pick),
    confirmLabel: String = stringResource(R.string.action_join),
    autoFocus: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    val palette = LocalBitchatPalette.current
    // Pre-fill with the cursor at the end of the existing name, not the start.
    var name by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialNickname,
                selection = TextRange(initialNickname.length),
            )
        )
    }


    WearFormScreen(
        edgeButton = {
            FormEdgeButton(
                label = confirmLabel,
                enabled = name.text.isNotBlank(),
                onClick = { if (name.text.isNotBlank()) onConfirm(name.text.trim()) },
            )
        }
    ) {
        formHeader(title = title, body = subtitle)
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            var field by remember { mutableStateOf<android.widget.EditText?>(null) }
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(FieldShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.primary, FieldShape)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                // A platform field, so full-screen watch keyboards show what is typed (see WatchEditText).
                WatchEditText(
                    text = name.text,
                    onTextChange = { typed ->
                        val trimmed = typed.trim().take(24)
                        name = TextFieldValue(trimmed, TextRange(trimmed.length))
                    },
                    hint = stringResource(R.string.nickname_title),
                    autoFocus = autoFocus,
                    onSend = {
                        field?.let {
                            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.hideSoftInputFromWindow(it.windowToken, 0)
                        }
                    },
                    editTextRef = { field = it },
                    imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
                    centered = true,
                    maxLength = 24,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                )
                // The pencil makes the pre-filled name read as editable, not a label.
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.align(Alignment.CenterEnd).size(12.dp),
                )
            }
        }
    }
}

internal val HiddenHandleSelectionColors = TextSelectionColors(
    handleColor = Color.Transparent,
    backgroundColor = Color(0x6632D74B),
)

private val FieldShape = RoundedCornerShape(18.dp)
