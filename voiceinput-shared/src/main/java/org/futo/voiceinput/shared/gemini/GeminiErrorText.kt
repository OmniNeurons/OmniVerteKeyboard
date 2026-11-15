package org.futo.voiceinput.shared.gemini

import androidx.annotation.StringRes
import org.futo.voiceinput.shared.R

/** OmniVerte: user-facing text for a Gemini failure kind. */
@StringRes
fun GeminiLiveException.UserKind.messageRes(): Int = when (this) {
    GeminiLiveException.UserKind.AUTH -> R.string.omniverte_gemini_error_auth
    GeminiLiveException.UserKind.QUOTA -> R.string.omniverte_gemini_error_quota
    GeminiLiveException.UserKind.RATE -> R.string.omniverte_gemini_error_rate
    GeminiLiveException.UserKind.NETWORK -> R.string.omniverte_gemini_error_network
    GeminiLiveException.UserKind.UNKNOWN -> R.string.omniverte_gemini_error_unknown
}
