package org.futo.voiceinput.shared.gemini

// OmniVerte: streaming client for Gemini 3.5 Transcribe Live.
// Port of desktop OmniVerte services/gemini_live.py (LiveSession), same contract:
// start() → sendPcm()* → finish() → text.
//
// PCM is pushed over the Live WebSocket while the user speaks; the client marks
// the turn itself: activityStart once the session is up, activityEnd on finish
// (server VAD disabled), so a thinking pause never freezes a segment. After
// activityEnd the server sends the finalized inputTranscription and
// generationComplete (~0.4 s measured on desktop).
//
// Failures (bad key, quota, wrong model) arrive as WebSocket close frames and
// surface from finish() as GeminiLiveException; the caller keeps its own copy of
// the audio and can replay it through local Whisper.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** One open WebSocket as the session sees it. */
interface LiveSocket {
    /** Queue a text frame; false if the socket is already closing/closed. */
    fun send(text: String): Boolean
    fun close()
}

/** Socket events, delivered on the transport's own thread. */
interface LiveSocketEvents {
    fun onOpen()
    fun onText(text: String)
    /** The server sent a close frame. */
    fun onServerClose(code: Int, reason: String)
    /** Handshake or connection failure; [httpStatus] is set when the handshake got an HTTP answer. */
    fun onFailure(error: Throwable, httpStatus: Int?)
}

/** Opens sockets; [OkHttpLiveTransport] in the app, a scripted fake in tests. */
fun interface LiveTransport {
    fun open(apiKey: String, events: LiveSocketEvents): LiveSocket
}

class GeminiLiveSession(
    private val apiKey: String,
    private val setup: JsonObject,
    private val transport: LiveTransport = OkHttpLiveTransport(),
    private val listener: Listener? = null,
    private val connectAttempts: Int = CONNECT_ATTEMPTS,
    /** Bounds finish()'s wait for setupComplete (all connect attempts + setup). */
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    interface Listener {
        /** Running transcript of the turn: finals so far + the current interim. */
        fun onPartial(text: String) {}
        /** The session failed (called once, never after close()). Lets the caller warm up a fallback early. */
        fun onFailed(error: GeminiLiveException) {}
    }

    private val lock = Any()
    private val changed = MutableStateFlow(0L)

    // --- state below is guarded by [lock] ---
    private var socket: LiveSocket? = null
    private var attempt = 0
    private var opened = false           // handshake done (onOpen)
    private var setupSent = false
    private var started = false          // setupComplete received
    private var closed = false           // socket is gone (failed, or closed by the server)
    private val pending = ByteArrayOutputStream()
    private val finals = mutableListOf<String>()
    private var interim = ""
    private var ended = false            // activityEnd sent
    private var complete = false         // generationComplete after activityEnd
    private var serverEnded = false      // server echoed ACTIVITY_END
    private var error: GeminiLiveException? = null
    private var closing = false

    /** True when finish() timed out and fell back to the last interim. */
    @Volatile
    var usedPartial = false
        private set

    /** Finalized transcripts as the server sent them (copy; diagnostics). */
    val segments: List<String>
        get() = synchronized(lock) { finals.toList() }

    /** The failure so far, if any (lets the recorder decide on a fallback before finish()). */
    val failure: GeminiLiveException?
        get() = synchronized(lock) { error }

    // -- lifecycle ---------------------------------------------------------

    /** Returns at once; connect + setup run on the transport's thread. */
    fun start() {
        connect()
    }

    /**
     * Queue captured 16 kHz mono PCM16; sent in 100 ms frames once the session
     * is up (audio before setupComplete is held and flushed after activityStart).
     * Never throws — a failure is recorded and surfaced by finish().
     */
    fun sendPcm(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[2 * i] = (s and 0xFF).toByte()             // little-endian
            bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        sendPcmBytes(bytes)
    }

    fun sendPcmBytes(pcm: ByteArray) {
        val failed = synchronized(lock) {
            if (error != null || closing) return
            pending.write(pcm)
            if (started) flushLocked(final = false) else null
        }
        notifyFailure(failed)
    }

    /**
     * End the turn and return the transcript.
     * Throws [GeminiLiveException] if the session failed; the caller then
     * replays its own copy of the audio through the fallback engine.
     */
    suspend fun finish(timeoutMs: Long = FINISH_TIMEOUT_MS): String {
        waitFor(connectTimeoutMs) { started || error != null || closed }
        val notStarted = synchronized(lock) {
            if (started) null
            else error ?: GeminiLiveException("Gemini Live session did not start in time")
        }
        if (notStarted != null) {
            close()
            throw notStarted
        }

        notifyFailure(synchronized(lock) { if (error == null) flushLocked(final = true) else null })

        val done = { complete || error != null || closed }
        waitFor(timeoutMs) { done() || serverEnded }
        if (synchronized(lock) { serverEnded && !done() }) {
            // The echo comes after the final when there was speech: allow a
            // straggling final, then return finals only.
            waitFor(FINAL_GRACE_MS, done)
        }

        val (err, parts) = synchronized(lock) {
            var parts = finals.toList()
            if (parts.isEmpty() && !serverEnded && interim.isNotBlank()) {
                // No final in time: the tail as the server last heard it. Not on
                // the silent-take path — an interim seen on silence is vocabulary
                // bias talking ("'TraderNet'" from 2 s of zeros).
                usedPartial = true
                parts = listOf(interim)
            }
            error to parts
        }
        close()
        if (err != null) throw err
        return joinParts(parts)
    }

    fun close() {
        val ws = synchronized(lock) {
            closing = true
            socket
        }
        try {
            ws?.close()
        } catch (_: Exception) {
        }
        changed.update { it + 1 }
    }

    // -- internals ---------------------------------------------------------

    private fun connect() {
        val events: Events
        synchronized(lock) {
            if (closing) return
            attempt += 1
            events = Events(attempt)
        }
        val ws = try {
            transport.open(apiKey, events)
        } catch (e: Exception) {
            events.onFailure(e, null)
            return
        }
        var stale = false
        val failed = synchronized(lock) {
            when {
                closing -> { stale = true; null }
                events.attemptNo != attempt -> null   // already failed and retried
                else -> {
                    socket = ws
                    // A transport may report onOpen before open() returns.
                    sendSetupLocked()
                }
            }
        }
        if (stale) ws.close()
        notifyFailure(failed)
    }

    /** Caller holds the lock. Sends `setup` once both the socket and onOpen are in. */
    private fun sendSetupLocked(): GeminiLiveException? {
        val ws = socket ?: return null
        if (!opened || setupSent) return null
        setupSent = true
        val setupMsg = buildJsonObject { put("setup", setup) }.toString()
        return if (ws.send(setupMsg)) null
        else failLocked(GeminiLiveException("Gemini Live send failed: socket closed"))
    }

    private suspend fun waitFor(timeoutMs: Long, predicate: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) {
            changed.first { synchronized(lock) { predicate() } }
        }
    }

    /** Caller holds the lock. Returns a new failure to report via [notifyFailure], if any. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun flushLocked(final: Boolean): GeminiLiveException? {
        val ws = socket ?: return null
        val data = pending.toByteArray()
        pending.reset()
        var offset = 0
        try {
            while (data.size - offset >= GeminiLive.CHUNK_BYTES || (final && offset < data.size)) {
                val end = minOf(offset + GeminiLive.CHUNK_BYTES, data.size)
                val b64 = Base64.Default.encode(data, offset, end)
                offset = end
                sendLocked(ws, realtimeInput {
                    putJsonObject("audio") {
                        put("data", b64)
                        put("mimeType", "audio/pcm;rate=${GeminiLive.SAMPLE_RATE}")
                    }
                })
            }
            if (offset < data.size) pending.write(data, offset, data.size - offset)
            if (final && !ended) {
                ended = true
                sendLocked(ws, realtimeInput { putJsonObject("activityEnd") {} })
            }
        } catch (e: SendFailed) {
            return failLocked(GeminiLiveException("Gemini Live send failed: socket closed"))
        }
        return null
    }

    private class SendFailed : Exception()

    private fun sendLocked(ws: LiveSocket, text: String) {
        if (!ws.send(text)) throw SendFailed()
    }

    /** Caller holds the lock. Records the first error; returns it if it is new. */
    private fun failLocked(err: GeminiLiveException): GeminiLiveException? {
        if (error != null) return null
        error = err
        log("Gemini Live: ${err.message}")
        changed.update { it + 1 }
        return if (closing) null else err
    }

    private fun notifyFailure(err: GeminiLiveException?) {
        if (err != null) listener?.onFailed(err)
    }

    private fun partialLocked(): String = joinParts(finals + interim)

    private inner class Events(val attemptNo: Int) : LiveSocketEvents {
        private fun isStale() = attemptNo != attempt

        override fun onOpen() {
            val failed = synchronized(lock) {
                if (isStale() || closing) return
                opened = true
                sendSetupLocked()
            }
            notifyFailure(failed)
        }

        override fun onText(text: String) {
            val msg = ServerMessage.parse(text) ?: return
            var partial: String? = null
            val failed = synchronized(lock) {
                if (isStale()) return
                var failed: GeminiLiveException? = null
                if (msg.setupComplete) {
                    log("Gemini Live: session started")
                    started = true
                    val ws = socket
                    failed = if (ws == null || !ws.send(realtimeInput { putJsonObject("activityStart") {} })) {
                        failLocked(GeminiLiveException("Gemini Live send failed: socket closed"))
                    } else {
                        flushLocked(final = ended)
                    }
                }
                if (msg.interim != null) {
                    interim = msg.interim
                    partial = partialLocked()
                }
                if (msg.final != null) {
                    finals.add(msg.final)
                    interim = ""
                    partial = partialLocked()
                }
                if (ended && msg.generationComplete) complete = true
                if (ended && msg.activityEndEcho) serverEnded = true
                changed.update { it + 1 }
                failed
            }
            notifyFailure(failed)
            partial?.let { p -> if (p.isNotBlank() && !closing) listener?.onPartial(p) }
        }

        override fun onServerClose(code: Int, reason: String) {
            val failed = synchronized(lock) {
                if (isStale()) return
                closed = true
                changed.update { it + 1 }
                // A normal close after the turn completed (or one we asked for)
                // is just the end of the session.
                if (closing || complete || code == 1000) null
                else failLocked(GeminiLive.closeError(code, reason))
            }
            notifyFailure(failed)
        }

        override fun onFailure(error: Throwable, httpStatus: Int?) {
            var retry = false
            val failed = synchronized(lock) {
                if (isStale()) return
                if (closing) {
                    closed = true
                    changed.update { it + 1 }
                    return
                }
                val err = when {
                    opened -> GeminiLiveException("Gemini Live connection lost: ${error.message}")
                    httpStatus != null -> GeminiLiveException(
                        "Gemini Live handshake HTTP $httpStatus",
                        GeminiLiveException.Kind.HTTP, status = httpStatus,
                    )
                    else -> GeminiLiveException("Gemini Live connection failed: ${error.message}")
                }
                // Only a plain network failure of the handshake is worth a retry.
                if (!opened && httpStatus == null && attemptNo < connectAttempts) {
                    log("Gemini Live: connect attempt $attemptNo failed (${error.message}), retrying")
                    socket = null
                    opened = false
                    setupSent = false
                    retry = true
                    null
                } else {
                    closed = true
                    failLocked(err)
                }
            }
            notifyFailure(failed)
            if (retry) connect()
        }
    }

    companion object {
        const val CONNECT_ATTEMPTS = 2
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 4000L
        const val CONNECT_TIMEOUT_MS = CONNECT_ATTEMPTS * CONNECT_ATTEMPT_TIMEOUT_MS + 2000L

        /** Wait for generationComplete after activityEnd (normally ~0.4 s). */
        const val FINISH_TIMEOUT_MS = 5000L

        /** After the ACTIVITY_END echo, extra wait for a straggling final. */
        const val FINAL_GRACE_MS = 300L

        internal fun joinParts(parts: List<String>): String =
            parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
