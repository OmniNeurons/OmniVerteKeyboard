package org.futo.voiceinput.shared.gemini

// OmniVerte: pure (Android-free, JVM-testable) protocol pieces of the Gemini
// 3.5 Transcribe Live client. Port of desktop OmniVerte services/gemini_live.py
// (build_setup, close_error) + services/api_errors.classify_api_error.

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object GeminiLive {
    const val LIVE_URL = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val MODEL_ID = "gemini-3.5-transcribe-live"
    const val SAMPLE_RATE = 16000

    /** 100 ms of 16 kHz mono int16 — the chunk size the Live docs recommend. */
    const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10

    /** Server-side cap on customVocabulary terms (same as the batch Gemini API). */
    const val MAX_VOCABULARY_TERMS = 100

    /**
     * Whisper short codes → the BCP-47 codes the Live model lists as supported.
     * An unsupported code is rejected and would fail every session, so anything
     * unmapped is left out (the server auto-detects then).
     */
    val LANGUAGE_CODES: Map<String, String> = mapOf(
        "en" to "en-US",
        "ru" to "ru-RU",
        "es" to "es-US",
        "fr" to "fr-FR",
        "de" to "de-DE",
        "it" to "it-IT",
        "zh" to "cmn-Hans-CN",
        "ja" to "ja-JP",
    )

    /**
     * The `setup` message body (no API key inside).
     *
     * Server VAD is disabled: the client marks the turn with activityStart /
     * activityEnd. VERBATIM is the server default but stated explicitly — SMART
     * rewrote words in the desktop A/B and is deliberately not used; fillers are
     * stripped locally instead.
     */
    fun buildSetup(languages: List<String?> = emptyList(), vocabulary: List<String> = emptyList()): JsonObject {
        val codes = languages.mapNotNull { LANGUAGE_CODES[it ?: ""] }.distinct()
        val terms = vocabulary.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_VOCABULARY_TERMS)
        return buildJsonObject {
            put("model", "models/$MODEL_ID")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")) }
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") { put("disabled", true) }
            }
            putJsonObject("inputAudioTranscription") {
                put("languageCodes", buildJsonArray { codes.forEach { add(JsonPrimitive(it)) } })
                put("mode", "VERBATIM")
                if (terms.isNotEmpty()) {
                    put("customVocabulary", JsonArray(terms.map { JsonPrimitive(it) }))
                }
            }
        }
    }

    /**
     * A server close frame → GeminiLiveException shaped like an HTTP error.
     * The Live API reports failures only as close code + reason text (verified:
     * 1007 "API key not valid", 1008 "model … not found").
     */
    fun closeError(code: Int?, reason: String): GeminiLiveException {
        val low = reason.lowercase()
        val status = when {
            "api key" in low || "permission" in low || "unauthenticated" in low -> 401
            // "rate" as a whole word only: the 1008 reason for a wrong model says
            // "...not supported for bidiGenerateContent", and a bare substring
            // match would turn that into a rate limit.
            "quota" in low || "resource_exhausted" in low || RATE_WORD.containsMatchIn(low) -> 429
            else -> null
        }
        val message = "Gemini Live closed ($code): $reason"
        return if (status == null) {
            GeminiLiveException(message, GeminiLiveException.Kind.RESPONSE, body = reason)
        } else {
            GeminiLiveException(message, GeminiLiveException.Kind.HTTP, status = status, body = reason)
        }
    }

    private val RATE_WORD = Regex("\\brate\\b")
}

/** Parsed fields of one server message that the session cares about. */
data class ServerMessage(
    val setupComplete: Boolean = false,
    /** Non-null when the message carried an interim transcription (may be ""). */
    val interim: String? = null,
    /** Non-empty when the message carried a finalized transcription. */
    val final: String? = null,
    val generationComplete: Boolean = false,
    /** The server's echo of our activityEnd (`voiceActivity.type == ACTIVITY_END`). */
    val activityEndEcho: Boolean = false,
) {
    companion object {
        /** Null for frames that are not JSON objects (ignored by the session). */
        fun parse(raw: String): ServerMessage? {
            val obj = try {
                kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null

            val content = obj["serverContent"] as? JsonObject
            fun JsonObject?.text(key: String): String? =
                ((this?.get(key) as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
            fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

            val voiceType = ((obj["voiceActivity"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            return ServerMessage(
                setupComplete = "setupComplete" in obj,
                interim = content.text("interimInputTranscription"),
                final = content.text("inputTranscription")?.takeIf { it.isNotEmpty() },
                generationComplete = content?.get("generationComplete").isTrue() ||
                        content?.get("turnComplete").isTrue(),
                activityEndEcho = voiceType == "ACTIVITY_END",
            )
        }
    }
}

/**
 * A Gemini Live failure. [kind] is HTTP (carries [status]/[body]), NETWORK
 * (DNS/timeout/connection — may never have reached Google) or RESPONSE (the
 * server answered with something undocumented).
 */
class GeminiLiveException(
    message: String,
    val kind: Kind = Kind.NETWORK,
    val status: Int? = null,
    val body: String = "",
) : Exception(message) {
    enum class Kind { HTTP, NETWORK, RESPONSE }

    /** User-actionable classification, mirrors desktop api_errors.classify_api_error. */
    enum class UserKind { AUTH, QUOTA, RATE, NETWORK, UNKNOWN }

    val userKind: UserKind
        get() = when (kind) {
            Kind.NETWORK -> UserKind.NETWORK
            Kind.HTTP -> when (status) {
                401, 403 -> UserKind.AUTH
                429 -> if ("quota" in body.lowercase()) UserKind.QUOTA else UserKind.RATE
                402 -> UserKind.QUOTA
                else -> UserKind.UNKNOWN
            }
            Kind.RESPONSE -> UserKind.UNKNOWN
        }
}

internal fun realtimeInput(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject { putJsonObject("realtimeInput", build) }.toString()
