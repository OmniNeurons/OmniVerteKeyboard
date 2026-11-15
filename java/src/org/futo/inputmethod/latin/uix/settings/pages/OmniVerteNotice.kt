package org.futo.inputmethod.latin.uix.settings.pages

// OmniVerte: "modified build" notice required by the FUTO Source First License
// ("a prominent notice stating that you have modified the software").

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.theme.Typography

@Composable
fun OmniVerteModifiedNotice() {
    Text(
        stringResource(R.string.omniverte_modified_notice),
        style = Typography.Small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        textAlign = TextAlign.Center
    )
}
