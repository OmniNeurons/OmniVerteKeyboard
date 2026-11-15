package org.futo.inputmethod.latin.uix.settings.pages

// OmniVerte: the user's terms & names list for Gemini transcription
// (sent as customVocabulary). One list, empty by default, filled by the user.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.GEMINI_TERMS
import org.futo.inputmethod.latin.uix.parseGeminiTerms
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.voiceinput.shared.gemini.GeminiLive

@Composable
private fun GeminiTermsEditor() {
    val context = LocalContext.current
    val stored = useDataStore(GEMINI_TERMS)
    var text by remember { mutableStateOf(stored.value) }
    var status by remember { mutableStateOf<String?>(null) }

    val count = parseGeminiTerms(text).size
    val dirty = parseGeminiTerms(text) != parseGeminiTerms(stored.value)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.omniverte_gemini_terms_help), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it; status = null },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp),
            label = { Text(stringResource(R.string.omniverte_gemini_terms_field)) },
            placeholder = { Text("OmniVerte\nTraderNet\n…") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )

        Text(
            stringResource(R.string.omniverte_gemini_terms_count, count, GeminiLive.MAX_VOCABULARY_TERMS),
            style = MaterialTheme.typography.bodySmall,
            color = if (count > GeminiLive.MAX_VOCABULARY_TERMS) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(enabled = dirty, onClick = {
            // Normalized: one trimmed term per line, no blanks or duplicates.
            val normalized = parseGeminiTerms(text).joinToString("\n")
            stored.setValue(normalized)
            text = normalized
            status = context.getString(R.string.omniverte_gemini_terms_saved)
        }) { Text(stringResource(R.string.omniverte_gemini_key_save)) }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

val GeminiTermsMenu = UserSettingsMenu(
    title = R.string.omniverte_gemini_terms,
    navPath = "voiceInput/geminiTerms", registerNavPath = true,
    settings = listOf(
        UserSetting(name = R.string.omniverte_gemini_terms) { GeminiTermsEditor() }
    )
)
