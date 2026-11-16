package com.bitchat.watch.ui

import androidx.compose.ui.graphics.toArgb
import com.bitchat.watch.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/**
 * Full-screen text input: field auto-focused so the watch IME (with its built-in dictation)
 * opens immediately, plus a dedicated dictation button using the system speech recognizer.
 * The line above the field names the destination, so a message is never sent to the wrong
 * place; the two round buttons match the chat action bar.
 */
@Composable
fun TextInputScreen(
    recipient: String?,
    autoFocus: Boolean = true,
    onSend: (String) -> Unit,
) {
    val palette = LocalBitchatPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var editTextRef by remember { mutableStateOf<android.widget.EditText?>(null) }

    val dictationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                WearHaptics.tick(context)
                onSend(spoken.trim())
            }
        }
    }

    fun send() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            editTextRef?.let { field ->
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
            }
            WearHaptics.tick(context)
            onSend(trimmed)
            text = ""
        }
    }

    // The screen can become active after it was composed (pager); focus then.
    LaunchedEffect(autoFocus, editTextRef) {
        val field = editTextRef
        if (autoFocus && field != null && !field.hasFocus()) {
            field.requestFocus()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    val canSend = text.isNotBlank()
    val sendContainer by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.primary else palette.inputButton,
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "sendContainer",
    )
    val sendIcon by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.onPrimary else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "sendIcon",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = recipient?.let { stringResource(R.string.input_to, it) } ?: stringResource(R.string.input_to_mesh),
            style = ChatVisualTokens.SystemActionStyle,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        WatchEditText(
            text = text,
            onTextChange = { text = it },
            hint = stringResource(R.string.input_hint),
            autoFocus = autoFocus,
            onSend = { send() },
            editTextRef = { editTextRef = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, palette.inputOutline, FieldShape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundActionButton(
                onClick = {
                    dictationLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak")
                        }
                    )
                },
                container = palette.inputButton,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.cd_dictate),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(ActionIconSize)
                )
            }
            RoundActionButton(
                onClick = { send() },
                enabled = canSend,
                container = sendContainer,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.cd_send),
                    tint = sendIcon,
                    modifier = Modifier.size(ActionIconSize)
                )
            }
        }
    }
}

private val FieldShape = RoundedCornerShape(18.dp)

/**
 * The message field, as a platform EditText rather than a Compose text field.
 *
 * Watch keyboards such as Samsung's run full screen and show their own preview of the text. They
 * keep that preview up to date through the platform's "extracted text" updates, which Compose
 * text fields do not send, so the preview froze after the first letter. EditText sends them.
 */
@Composable
internal fun WatchEditText(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    autoFocus: Boolean,
    onSend: () -> Unit,
    editTextRef: (android.widget.EditText) -> Unit = {},
    modifier: Modifier = Modifier,
    /** EditorInfo.IME_ACTION_SEND (messages) or IME_ACTION_DONE (forms). */
    imeAction: Int = android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
    centered: Boolean = false,
    maxLength: Int? = null,
    numeric: Boolean = false,
) {
    val palette = LocalBitchatPalette.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = palette.textTertiary.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()
    val currentOnTextChange by androidx.compose.runtime.rememberUpdatedState(onTextChange)
    val currentOnSend by androidx.compose.runtime.rememberUpdatedState(onSend)
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                background = null
                setPadding(0, 0, 0, 0)
                isSingleLine = true
                textSize = ChatVisualTokens.MessageBodyStyle.fontSize.value
                setTextColor(textColor)
                setHintTextColor(hintColor)
                this.hint = hint
                textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(cursorColor)
                    setSize((2 * ctx.resources.displayMetrics.density).toInt(), 0)
                }
                inputType = if (numeric) {
                    android.text.InputType.TYPE_CLASS_NUMBER
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                }
                imeOptions = imeAction
                if (centered) gravity = android.view.Gravity.CENTER
                maxLength?.let { filters = arrayOf(android.text.InputFilter.LengthFilter(it)) }
                setText(text)
                setSelection(text.length)
                setOnEditorActionListener { _, actionId, event ->
                    val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN
                    if (actionId == imeAction || enter) {
                        currentOnSend()
                        true
                    } else {
                        false
                    }
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        currentOnTextChange(s?.toString().orEmpty())
                    }
                })
                editTextRef(this)
                if (autoFocus) {
                    post {
                        requestFocus()
                        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        },
        update = { field ->
            // Only push text in from outside (e.g. cleared after sending); typing flows the other way.
            if (field.text.toString() != text) {
                field.setText(text)
                field.setSelection(text.length)
            }
        }
    )
}
