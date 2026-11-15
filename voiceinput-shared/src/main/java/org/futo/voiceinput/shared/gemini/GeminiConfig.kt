package org.futo.voiceinput.shared.gemini

/**
 * OmniVerte: turns the Gemini Live engine on for one recognition. Null in
 * AudioRecognizerSettings means stock FUTO behaviour (local Whisper only).
 */
data class GeminiConfig(
    val apiKey: String,
    /** Whisper short codes of the active keyboard languages, primary first. */
    val languages: List<String>,
    /** customVocabulary terms (FUTO's personal dictionary when that setting is on). */
    val vocabulary: List<String> = emptyList(),
    val cleanFillers: Boolean = true,
    /** VAD auto-stop after this much silence (FUTO's local default is ~2 s). */
    val silenceStopMs: Long = DEFAULT_SILENCE_STOP_MS,
    /**
     * A Whisper model for the language is installed, so a failed Gemini take
     * (offline, bad key, quota) is replayed locally. False: the model in the
     * run configuration is only a placeholder and must not be touched.
     */
    val whisperFallback: Boolean = true,
) {
    override fun toString(): String =     // never print the key
        "GeminiConfig(languages=$languages, vocabulary=${vocabulary.size} terms, cleanFillers=$cleanFillers, " +
                "silenceStopMs=$silenceStopMs, whisperFallback=$whisperFallback)"

    companion object {
        const val DEFAULT_SILENCE_STOP_MS = 5000L
        const val MIN_SILENCE_STOP_MS = 5000L
    }
}
