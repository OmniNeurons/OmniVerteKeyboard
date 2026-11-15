package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

val ENABLE_SOUND = SettingsKey(
    key = booleanPreferencesKey("enable_sounds"),
    default = true
)

val VERBOSE_PROGRESS = SettingsKey(
    key = booleanPreferencesKey("verbose_progress"),
    default = false
)

val ENABLE_ENGLISH = SettingsKey(
    key = booleanPreferencesKey("enable_english"),
    default = true
)

val ENABLE_MULTILINGUAL = SettingsKey(
    key = booleanPreferencesKey("enable_multilingual"),
    default = false
)

val DISALLOW_SYMBOLS = SettingsKey(
    key = booleanPreferencesKey("disallow_symbols"),
    default = true
)

val PREFER_BLUETOOTH = SettingsKey(
    key = booleanPreferencesKey("prefer_bluetooth_recording"),
    default = false
)

val CAN_EXPAND_SPACE = SettingsKey(
    key = booleanPreferencesKey("can_expand_space"),
    default = true
)

val AUDIO_FOCUS = SettingsKey(
    key = booleanPreferencesKey("request_audio_focus"),
    default = true
)

val USE_VAD_AUTOSTOP = SettingsKey(
    key = booleanPreferencesKey("use_vad_autostop"),
    default = true
)

val ENGLISH_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("english_model_index"),
    default = 0
)

val MULTILINGUAL_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("multilingual_model_index"),
    default = 1
)

val LANGUAGE_TOGGLES = SettingsKey(
    key = stringSetPreferencesKey("enabled_languages"),
    default = setOf()
)

val USE_PERSONAL_DICT = SettingsKey(
    key = booleanPreferencesKey("use_personal_dict_voice_input"),
    default = true
)

val ANIMATE_BUBBLE = SettingsKey(
    key = booleanPreferencesKey("animate_bubble"),
    default = true
)
// OmniVerte: Gemini voice engine (the API key itself lives in GeminiVoiceKey, not here)
val GEMINI_VOICE_ENABLED = SettingsKey(
    key = booleanPreferencesKey("omniverte_gemini_voice_enabled"),
    default = true
)

val GEMINI_CLEAN_FILLERS = SettingsKey(
    key = booleanPreferencesKey("omniverte_gemini_clean_fillers"),
    default = true
)

/** VAD auto-stop pause in Gemini mode, seconds (never below 5). */
val GEMINI_SILENCE_STOP_SECONDS = SettingsKey(
    key = intPreferencesKey("omniverte_gemini_silence_stop_seconds"),
    default = 5
)

/** User's terms & names for Gemini customVocabulary, one per line. Empty by default. */
val GEMINI_TERMS = SettingsKey(
    key = androidx.datastore.preferences.core.stringPreferencesKey("omniverte_gemini_terms"),
    default = ""
)

/** Parsed GEMINI_TERMS: trimmed, non-empty, de-duplicated, in the user's order. */
fun parseGeminiTerms(raw: String): List<String> =
    raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
