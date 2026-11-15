package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.gemini.GeminiConfig
import org.futo.voiceinput.shared.gemini.GeminiLive
import org.futo.voiceinput.shared.gemini.GeminiLiveException
import org.futo.voiceinput.shared.gemini.GeminiLiveSession
import org.futo.voiceinput.shared.gemini.TextCleanup
import org.futo.voiceinput.shared.gemini.messageRes
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.VoiceEngine
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean
)

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings,
    // OmniVerte: non-null = stream to Gemini Live, Whisper only as a fallback
    val gemini: GeminiConfig? = null
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

private const val TAG = "AudioRecognizer"

class AudioRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    modelManager: ModelManager,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings
) {
    private var isRecording = false
    private var recorder: AudioRecord? = null

    private val modelRunner = MultiModelRunner(modelManager)

    // OmniVerte: Gemini engine state
    private val gemini = settings.gemini
    private var session: GeminiLiveSession? = null

    // Gemini takes are not bound by Whisper's 30 s window, but the fallback
    // replays the buffer, so it must keep growing.
    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace || gemini != null
    private val useVAD = settings.recordingConfiguration.useVADAutoStop

    // VAD frames are 480 samples = 30 ms. FUTO's local default: 66 (~2 s).
    private val silenceStopFrames = gemini?.let {
        (maxOf(it.silenceStopMs, GeminiConfig.MIN_SILENCE_STOP_MS) / 30).toInt()
    } ?: 66

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null

    private var focusRequest: AudioFocusRequest? = null

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices

                return devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } != null
            }
        } catch(_: Exception) {}

        return false
    }

    private fun setCommunicationDevice(preferBluetoothMic: Boolean): Pair<Boolean, String> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices
                val tgtDevice =
                    devices.firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?:
                    devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO  } ?:
                    devices.first()

                if (!audioManager.setCommunicationDevice(tgtDevice)) {
                    audioManager.clearCommunicationDevice()
                    return Pair(false, "")
                } else {
                    return Pair(tgtDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO, tgtDevice.productName.toString())
                }
            }
        } catch(_: Exception) {}
        return Pair(false, "")
    }

    private fun clearCommunicationDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    // Stock FUTO: a missing model is an error (shows the "no model" screen).
    // Gemini: the model is only the fallback; without it a failed take reports
    // the error instead.
    private val whisperAvailable: Boolean = if (gemini == null) {
        verifyModelsExist()
        true
    } else {
        gemini.whisperFallback && try {
            verifyModelsExist()
            true
        } catch (_: ModelDoesNotExistException) {
            false
        }
    }

    fun reset() {
        recorder?.stop()
        recorderJob?.cancel()

        recorder?.release()
        recorder = null

        modelJob?.cancel()
        isRecording = false

        session?.close()
        session = null

        modelRunner.cancelAll()

        unfocusAudio()

        clearCommunicationDevice()
    }

    fun finish() {
        if(!isRecording) return
        onFinishRecording()
    }

    fun cancel() {
        reset()
        listener.cancelled()
    }

    fun openPermissionSettings() {
        val packageName = context.packageName
        val micPermissionRequester = Intent()
        micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        micPermissionRequester.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(micPermissionRequester)

        cancel()
    }

    fun start() {
        listener.loading()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
        } else {
            startRecording()
        }
    }

    private fun requestPermission() {
        listener.needPermission { wasGranted ->
            if(wasGranted) {
                startRecording()
            }
        }
    }


    @Throws(SecurityException::class)
    private fun createAudioRecorder(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )

        this.recorder = recorder

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
        }

        return recorder
    }

    private suspend fun preloadModels() {
        modelRunner.preload(settings.modelRunConfiguration)
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            //Log.d("AudioRecognizer", "Allocating extra space: ${floatSamples.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }

    private suspend fun recordingJob(recorder: AudioRecord, vad: VadModel?) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0

        val samples = ShortArray(1600)

        while (isRecording) {
            yield()
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            yield()

            var isRunningOutOfSpace = (floatSamples.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed()

            val hasNotTalkedRecently = hasTalked && (numConsecutiveNonSpeech > silenceStopFrames) && useVAD
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                yield()
                withContext(Dispatchers.Main) {
                    finish()
                }
                return
            }

            // Run VAD
            if(useVAD && vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            floatSamples.put(samples.sliceArray(0 until nRead).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
            session?.sendPcm(samples, nRead)

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
            }

            val rms = sqrt(samples.sumOf { (it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2).toDouble() } / samples.size).toFloat()

            if (startSoundPassed && ((rms > 0.01) || (numConsecutiveSpeech > 8))) {
                hasTalked = true
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (floatSamples.position() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(Dispatchers.Main) {
                listener.updateMagnitude(magnitude, state)
            }

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (nRead2 > 0) {
                    if (floatSamples.remaining() < nRead2 && !expandSpaceIfAllowed()) {
                        yield()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                        break
                    }
                    floatSamples.put(samples.sliceArray(0 until nRead2).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
                    session?.sendPcm(samples, nRead2)
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    @Throws(SecurityException::class)
    private fun createRecorderAndJob(preferBluetoothMic: Boolean): MicrophoneDeviceState {
        isRecording = false
        recorder?.stop()

        val bluetoothInfo = setCommunicationDevice(preferBluetoothMic)

        val task = {
            recorder?.release()

            val recorder = createAudioRecorder()

            recorder.startRecording()
            this.recorder = recorder

            isRecording = true

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    if(useVAD) {
                        createVad().use { vad ->
                            recordingJob(recorder, vad)
                        }
                    } else {
                        recordingJob(recorder, null)
                    }
                }
            }
        }

        if(recorderJob != null) {
            lifecycleScope.launch {
                recorderJob?.cancelAndJoin()
                task()
            }
        } else {
            task()
        }


        return MicrophoneDeviceState(
            bluetoothAvailable = bluetoothInfo.first || isBluetoothAvailable(),
            bluetoothActive = bluetoothInfo.first,
            deviceName = bluetoothInfo.second,
            bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
            setBluetooth = {
                listener.recordingStarted(createRecorderAndJob(it))
            }
        )
    }

    private fun startRecording() {
        val device = try {
            createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic)
        } catch (e: SecurityException) {
            // It's possible we may have lost permission, so let's just ask for permission again
            clearCommunicationDevice()
            requestPermission()
            return
        }

        focusAudio()

        listener.recordingStarted(device)

        if (gemini != null) {
            listener.engineChanged(VoiceEngine.GEMINI)
            startGeminiSession(gemini)
            return
        }
        listener.engineChanged(VoiceEngine.WHISPER)

        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                } catch(_: InvalidModelException) {
                    withContext(Dispatchers.Main) {
                        reset()
                        listener.modelLoadingFailed()
                    }
                }
            }
        }
    }

    // OmniVerte: open the Live session alongside the recorder (the start sound
    // covers the connect). Whisper is warmed up only once the session fails.
    private fun startGeminiSession(config: GeminiConfig) {
        session?.close()
        Log.i(TAG, "Gemini session: $config")   // GeminiConfig.toString() omits the key
        var self: GeminiLiveSession? = null
        val s = GeminiLiveSession(
            apiKey = config.apiKey,
            setup = GeminiLive.buildSetup(config.languages, config.vocabulary),
            listener = object : GeminiLiveSession.Listener {
                override fun onPartial(text: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (session === self && isRecording) {
                            listener.partialResult(TextCleanup.forDictation(text, config.cleanFillers))
                        }
                    }
                }

                override fun onFailed(error: GeminiLiveException) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (session !== self) return@launch
                        listener.engineChanged(
                            if (whisperAvailable) VoiceEngine.WHISPER_FALLBACK else VoiceEngine.GEMINI_FAILED
                        )
                        if (whisperAvailable) preloadFallback()
                    }
                }
            },
            log = { Log.i(TAG, it) }
        )
        self = s
        session = s
        s.start()
    }

    private fun preloadFallback() {
        if (loadModelJob != null) return
        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                } catch (e: InvalidModelException) {
                    Log.w(TAG, "Whisper fallback model failed to load", e)
                }
            }
        }
    }

    /** Gemini path of runModel(). Returns false when the take must go to Whisper. */
    private suspend fun runGemini(config: GeminiConfig, s: GeminiLiveSession): Boolean {
        val error = try {
            val text = TextCleanup.forDictation(s.finish(), config.cleanFillers)
            Log.i(TAG, "Gemini take: ${text.length} chars, usedPartial=${s.usedPartial}")
            deliver(text)
            return true
        } catch (e: GeminiLiveException) {
            Log.w(TAG, "Gemini take failed (${e.userKind}): ${e.message}")
            e
        }

        val reason = context.getString(error.userKind.messageRes())
        val message = context.getString(
            if (whisperAvailable) R.string.omniverte_gemini_fallback_local
            else R.string.omniverte_gemini_no_fallback,
            reason
        )
        withContext(Dispatchers.Main) {
            listener.engineChanged(
                if (whisperAvailable) VoiceEngine.WHISPER_FALLBACK else VoiceEngine.GEMINI_FAILED
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        if (!whisperAvailable) {
            deliver("")
            return true
        }
        return false
    }

    private fun deliver(text: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                yield()
                listener.finished(text)
            }
        }
    }

    private val runnerCallback: ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {
            listener.decodingStatus(state)
        }

        override fun languageDetected(language: Language) {
            listener.languageDetected(language)
        }

        override fun partialResult(string: String) {
            if(isBlankResult(string)) return
            listener.partialResult(string)
        }
    }

    private suspend fun runModel() {
        val s = session
        if (gemini != null && s != null && runGemini(gemini, s)) return

        loadModelJob?.let {
            if (it.isActive) {
                println("Model was not finished loading...")
                it.join()
            }
        }

        val floatArray = floatSamples.array().sliceArray(0 until floatSamples.position())

        yield()
        val outputText = try {
             modelRunner.run(
                floatArray,
                settings.modelRunConfiguration,
                settings.decodingConfiguration,
                runnerCallback
            ).trim()
        }catch(e: InferenceCancelledException) {
            yield()
            return
        }

        val text = when {
            isBlankResult(outputText) -> ""
            else -> outputText
        }

        yield()
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                yield()
                listener.finished(text)
            }
        }
    }

    private fun onFinishRecording() {
        recorderJob?.cancel()

        if (!isRecording) {
            throw IllegalStateException("Should not call onFinishRecording when not recording")
        }

        isRecording = false
        recorder?.stop()

        listener.processing()

        modelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runModel()
            }
        }
    }
}