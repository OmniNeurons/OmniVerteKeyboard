package org.futo.voiceinput.shared.types

import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

enum class MagnitudeState {
    NOT_TALKED_YET, MIC_MAY_BE_BLOCKED, TALKING
}

/** OmniVerte: which engine is transcribing the current take (shown in the voice window). */
enum class VoiceEngine {
    GEMINI, WHISPER, WHISPER_FALLBACK, GEMINI_FAILED
}

interface AudioRecognizerListener {
    fun cancelled()
    fun finished(result: String)
    fun languageDetected(language: Language)
    fun partialResult(result: String)
    fun decodingStatus(status: InferenceState)
    fun modelLoadingFailed()

    fun loading()
    fun needPermission(onResult: (Boolean) -> Unit)

    fun recordingStarted(device: MicrophoneDeviceState)
    fun updateMagnitude(magnitude: Float, state: MagnitudeState)

    fun processing()

    // OmniVerte
    fun engineChanged(engine: VoiceEngine) {}
}