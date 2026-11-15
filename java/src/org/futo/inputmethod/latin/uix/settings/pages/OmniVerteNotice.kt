package org.futo.inputmethod.latin.uix.settings.pages

// OmniVerte: "modified build" notice required by the FUTO Source First License,
// with credit to OmniNeurons and a link to OmniVerte (the desktop app this
// keyboard's voice input is modelled on).

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.updates.openURI

const val OMNIVERTE_URL = "https://omniverte.ai/"

@Composable
fun OmniVerteModifiedNotice() {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.omniverte_modified_notice),
            style = Typography.Small,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.omniverte_based_on),
            style = Typography.Small,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { context.openURI(OMNIVERTE_URL, true) }
                .padding(vertical = 4.dp),
            textAlign = TextAlign.Center
        )
    }
}
