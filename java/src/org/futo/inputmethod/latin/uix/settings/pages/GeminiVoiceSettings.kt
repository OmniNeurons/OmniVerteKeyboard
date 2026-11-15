package org.futo.inputmethod.latin.uix.settings.pages

// OmniVerte: Gemini API key entry on the voice input settings page.
// Manual entry or paste from the clipboard; after a paste the clipboard is
// cleared and the key is dropped from FUTO's own clipboard history.

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.GeminiVoiceKey
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistoryManager
import org.futo.voiceinput.shared.gemini.GeminiLive
import org.futo.voiceinput.shared.gemini.GeminiLiveException
import org.futo.voiceinput.shared.gemini.GeminiLiveSession
import org.futo.voiceinput.shared.gemini.messageRes

private fun readClipboardText(context: Context): String? = try {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
} catch (_: Exception) {
    null
}

private fun forgetClipboard(context: Context, text: String) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    } catch (_: Exception) {
    }
    ClipboardHistoryManager.forgetTextFlow.tryEmit(text)
}

/** A silent 0.5 s take: a valid key answers with an empty transcript. */
private suspend fun checkKey(apiKey: String): GeminiLiveException? {
    val session = GeminiLiveSession(apiKey, GeminiLive.buildSetup(listOf("en")))
    session.start()
    session.sendPcmBytes(ByteArray(GeminiLive.CHUNK_BYTES * 5))
    return try {
        session.finish()
        null
    } catch (e: GeminiLiveException) {
        e
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeminiApiKeySetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var saved by remember { mutableStateOf(GeminiVoiceKey.read(context)) }
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun store(key: String) {
        GeminiVoiceKey.save(context, key)
        saved = GeminiVoiceKey.read(context)
        input = ""
        status = context.getString(R.string.omniverte_gemini_key_saved)
    }

    val pasteFailed = stringResource(R.string.omniverte_gemini_key_clipboard_empty)
    val checkingText = stringResource(R.string.omniverte_gemini_key_checking)
    val okText = stringResource(R.string.omniverte_gemini_key_ok)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.omniverte_gemini_key_title), style = MaterialTheme.typography.titleMedium)
        Text(
            saved?.let { stringResource(R.string.omniverte_gemini_key_set, GeminiVoiceKey.masked(it)) }
                ?: stringResource(R.string.omniverte_gemini_key_not_set),
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.omniverte_gemini_key_field)) },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            // Password type: the keyboard does not learn or suggest from this field.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(stringResource(if (visible) R.string.omniverte_gemini_key_hide else R.string.omniverte_gemini_key_show))
                }
            }
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val text = readClipboardText(context)
                if (text.isNullOrEmpty()) {
                    status = pasteFailed
                } else {
                    store(text)
                    forgetClipboard(context, text)
                    status = context.getString(R.string.omniverte_gemini_key_pasted)
                }
            }) { Text(stringResource(R.string.omniverte_gemini_key_paste)) }

            OutlinedButton(enabled = input.isNotBlank(), onClick = { store(input) }) {
                Text(stringResource(R.string.omniverte_gemini_key_save))
            }
        }

        val current = saved
        if (current != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !checking, onClick = {
                    checking = true
                    status = checkingText
                    scope.launch {
                        val err = checkKey(current)
                        status = if (err == null) okText else context.getString(err.userKind.messageRes())
                        checking = false
                    }
                }) { Text(stringResource(R.string.omniverte_gemini_key_check)) }

                TextButton(onClick = {
                    GeminiVoiceKey.clear(context)
                    saved = null
                    status = context.getString(R.string.omniverte_gemini_key_removed)
                }) { Text(stringResource(R.string.omniverte_gemini_key_remove)) }
            }
        }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
