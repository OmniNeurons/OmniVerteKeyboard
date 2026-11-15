package org.futo.voiceinput.shared.gemini

// Port of desktop OmniVerte tests/test_gemini_live.py: the session against a
// scripted fake server (events delivered on another thread, like OkHttp).

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors

class GeminiLiveProtocolTest {
    @Test
    fun buildSetupMapsLanguagesVocabularyAndDisablesServerVad() {
        val setup = GeminiLive.buildSetup(listOf("ru", "en", "ru", "xx"), listOf("OmniVerte", " ", "TraderNet"))
        assertEquals("models/gemini-3.5-transcribe-live", setup["model"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement("""{"automaticActivityDetection":{"disabled":true}}"""),
            setup["realtimeInputConfig"],
        )
        val tr = setup["inputAudioTranscription"]!!.jsonObject
        assertEquals(listOf("ru-RU", "en-US"), tr["languageCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("VERBATIM", tr["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf("OmniVerte", "TraderNet"), tr["customVocabulary"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun buildSetupAutoDetectAndVocabularyCap() {
        val tr = GeminiLive.buildSetup(listOf(null), (0 until 150).map { "t$it" })["inputAudioTranscription"]!!.jsonObject
        assertEquals(0, tr["languageCodes"]!!.jsonArray.size)
        assertEquals(GeminiLive.MAX_VOCABULARY_TERMS, tr["customVocabulary"]!!.jsonArray.size)
        val noVocab = GeminiLive.buildSetup(listOf("en"))["inputAudioTranscription"]!!.jsonObject
        assertFalse("customVocabulary" in noVocab)
    }

    @Test
    fun closeErrorIsClassifiedLikeBatchGemini() {
        assertEquals(GeminiLiveException.UserKind.AUTH,
            GeminiLive.closeError(1007, "API key not valid. Please pass a valid API key.").userKind)
        assertEquals(GeminiLiveException.UserKind.QUOTA,
            GeminiLive.closeError(1011, "RESOURCE_EXHAUSTED: You exceeded your current quota").userKind)
        assertEquals(GeminiLiveException.UserKind.UNKNOWN,
            GeminiLive.closeError(1008, "models/x is not found for API version v1beta").userKind)
    }

    @Test
    fun wrongModelReasonMentioningGenerateContentIsNotARateLimit() {
        val err = GeminiLive.closeError(1008,
            "models/x is not found for API version v1beta, or is not supported for bidiGenerateContent.")
        assertEquals(GeminiLiveException.UserKind.UNKNOWN, err.userKind)
        assertEquals(GeminiLiveException.UserKind.RATE,
            GeminiLive.closeError(1011, "Rate limit exceeded, retry later").userKind)
    }

    @Test
    fun handshakeHttpStatusesClassify() {
        fun http(status: Int) = GeminiLiveException("x", GeminiLiveException.Kind.HTTP, status = status)
        assertEquals(GeminiLiveException.UserKind.AUTH, http(403).userKind)
        assertEquals(GeminiLiveException.UserKind.RATE, http(429).userKind)
        assertEquals(GeminiLiveException.UserKind.NETWORK, GeminiLiveException("x").userKind)
    }

    @Test
    fun parseServerMessages() {
        assertNull(ServerMessage.parse("not json"))
        assertTrue(ServerMessage.parse("""{"setupComplete":{}}""")!!.setupComplete)
        val m = ServerMessage.parse("""{"serverContent":{"inputTranscription":{"text":"Привет."},"generationComplete":true}}""")!!
        assertEquals("Привет.", m.final)
        assertTrue(m.generationComplete)
        assertTrue(ServerMessage.parse("""{"serverContent":{"turnComplete":true}}""")!!.generationComplete)
        assertTrue(ServerMessage.parse("""{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_END"}}""")!!.activityEndEcho)
        assertNull(ServerMessage.parse("""{"serverContent":{"inputTranscription":{"text":""}}}""")!!.final)
    }
}

/** Scripted server: replies are queued in reaction to what the client sends. */
private class FakeServer(
    val onSend: FakeServer.(JsonObject) -> Unit = {},
) {
    val sent: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val thread = Executors.newSingleThreadExecutor()
    lateinit var events: LiveSocketEvents
    @Volatile var closed = false

    fun push(json: String) = thread.execute { events.onText(json) }
    fun pushClose(code: Int, reason: String) = thread.execute { events.onServerClose(code, reason) }

    fun socket(events: LiveSocketEvents): LiveSocket {
        this.events = events
        thread.execute { events.onOpen() }
        return object : LiveSocket {
            override fun send(text: String): Boolean {
                if (closed) return false
                val msg = Json.parseToJsonElement(text).jsonObject
                sent.add(msg)
                onSend(msg)
                return true
            }

            override fun close() {
                closed = true
            }
        }
    }

    /** "setup" / "activityStart" / "audio" / "activityEnd" for each sent message. */
    fun kinds(): List<String> = synchronized(sent) {
        sent.map { if ("setup" in it) "setup" else it["realtimeInput"]!!.jsonObject.keys.first() }
    }
}

private fun JsonObject.isActivityEnd() = (this["realtimeInput"] as? JsonObject)?.containsKey("activityEnd") == true
private fun JsonObject.isAudio() = (this["realtimeInput"] as? JsonObject)?.containsKey("audio") == true
private fun text(key: String, value: String) = """{"serverContent":{"$key":{"text":${JsonPrimitive(value)}}}}"""

private fun server(finalText: String? = "Плохо, что нет индикатора.", complete: Boolean = true) = FakeServer { msg ->
    if ("setup" in msg) push("""{"setupComplete":{}}""")
    else if (msg.isActivityEnd()) {
        if (finalText != null) push(text("inputTranscription", finalText))
        if (complete) push("""{"serverContent":{"generationComplete":true}}""")
    }
}

private fun session(
    fake: FakeServer,
    listener: GeminiLiveSession.Listener? = null,
) = GeminiLiveSession("k", GeminiLive.buildSetup(listOf("ru")), LiveTransport { _, ev -> fake.socket(ev) }, listener)

class GeminiLiveSessionTest {
    @Test
    fun sendsSetupActivityAudioAndReturnsFinal() = runBlocking {
        val fake = server()
        val s = session(fake)
        s.start()
        s.sendPcmBytes(ByteArray(GeminiLive.CHUNK_BYTES + 20) { 1 })   // 1 full chunk + a tail
        assertEquals("Плохо, что нет индикатора.", s.finish())
        val kinds = fake.kinds()
        assertEquals("setup", kinds[0])
        assertEquals("activityStart", kinds[1])
        assertEquals("activityEnd", kinds.last())
        assertEquals(2, kinds.count { it == "audio" })               // tail flushed on finish
        val audio = fake.sent[2]["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals(listOf("Плохо, что нет индикатора."), s.segments)
        assertFalse(s.usedPartial)
        assertTrue(fake.closed)
    }

    @Test
    fun pcmSamplesAreLittleEndianAndChunkedBy100ms() = runBlocking {
        val fake = server()
        val s = session(fake)
        s.start()
        s.sendPcm(ShortArray(1600) { 0x0102 })        // exactly 100 ms
        s.sendPcm(ShortArray(10) { 0x0102 }, count = 3)
        s.finish()
        val chunks = synchronized(fake.sent) {
            fake.sent.filter { it.isAudio() }.map {
                java.util.Base64.getDecoder().decode(it["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            }
        }
        assertEquals(listOf(GeminiLive.CHUNK_BYTES, 6), chunks.map { it.size })
        assertEquals(0x02, chunks[0][0].toInt())
        assertEquals(0x01, chunks[0][1].toInt())
    }

    @Test
    fun joinsSeveralFinals() = runBlocking {
        val fake = FakeServer { msg ->
            if ("setup" in msg) push("""{"setupComplete":{}}""")
            else if (msg.isActivityEnd()) {
                push(text("inputTranscription", "Первая часть."))
                push(text("inputTranscription", " Вторая часть."))
                push("""{"serverContent":{"generationComplete":true}}""")
            }
        }
        val s = session(fake)
        s.start()
        assertEquals("Первая часть. Вторая часть.", s.finish())
    }

    @Test
    fun partialsCarryFinalsPlusCurrentInterim() = runBlocking {
        val partials = Collections.synchronizedList(mutableListOf<String>())
        val fake = FakeServer { msg ->
            if ("setup" in msg) {
                push("""{"setupComplete":{}}""")
                push(text("interimInputTranscription", "первая"))
                push(text("inputTranscription", "Первая часть."))
                push(text("interimInputTranscription", "вто"))
            } else if (msg.isActivityEnd()) {
                push(text("inputTranscription", "Вторая."))
                push("""{"serverContent":{"generationComplete":true}}""")
            }
        }
        val s = session(fake, object : GeminiLiveSession.Listener {
            override fun onPartial(text: String) { partials.add(text) }
        })
        s.start()
        Thread.sleep(200)
        assertEquals("Первая часть. Вторая.", s.finish())
        assertEquals(listOf("первая", "Первая часть.", "Первая часть. вто"), partials.take(3))
    }

    @Test
    fun finishFallsBackToInterimOnTimeout() = runBlocking {
        val fake = FakeServer { msg ->
            if ("setup" in msg) push("""{"setupComplete":{}}""")
            else if (msg.isAudio()) push(text("interimInputTranscription", "почти готово"))
        }
        val s = session(fake)
        s.start()
        s.sendPcmBytes(ByteArray(GeminiLive.CHUNK_BYTES))
        assertEquals("почти готово", s.finish(timeoutMs = 300))
        assertTrue(s.usedPartial)
    }

    @Test
    fun serverCloseFrameRaisesClassifiedErrorAndNotifiesEarly() = runBlocking {
        var notified: GeminiLiveException? = null
        val fake = FakeServer { msg ->
            if ("setup" in msg) pushClose(1007, "API key not valid. Please pass a valid API key.")
        }
        val s = session(fake, object : GeminiLiveSession.Listener {
            override fun onFailed(error: GeminiLiveException) { notified = error }
        })
        s.start()
        try {
            s.finish()
            fail("expected GeminiLiveException")
        } catch (e: GeminiLiveException) {
            assertEquals(GeminiLiveException.UserKind.AUTH, e.userKind)
        }
        assertEquals(GeminiLiveException.UserKind.AUTH, notified?.userKind)
    }

    @Test
    fun connectNetworkFailureIsRetriedOnce() = runBlocking {
        var calls = 0
        val fake = server()
        val s = GeminiLiveSession("k", GeminiLive.buildSetup(), LiveTransport { _, ev ->
            calls++
            if (calls == 1) {
                Thread { ev.onFailure(IOException("blip"), null) }.start()
                object : LiveSocket {
                    override fun send(text: String) = false
                    override fun close() {}
                }
            } else fake.socket(ev)
        })
        s.start()
        assertEquals("Плохо, что нет индикатора.", s.finish())
        assertEquals(2, calls)
    }

    @Test
    fun connectHttpFailureIsNotRetried() = runBlocking {
        var calls = 0
        val s = GeminiLiveSession("k", GeminiLive.buildSetup(), LiveTransport { _, ev ->
            calls++
            Thread { ev.onFailure(IOException("Expected HTTP 101 but was 403"), 403) }.start()
            object : LiveSocket {
                override fun send(text: String) = false
                override fun close() {}
            }
        })
        s.start()
        try {
            s.finish()
            fail("expected GeminiLiveException")
        } catch (e: GeminiLiveException) {
            assertEquals(403, e.status)
            assertEquals(GeminiLiveException.UserKind.AUTH, e.userKind)
        }
        assertEquals(1, calls)
    }

    @Test
    fun offlineFailsFastWithoutWaitingForConnectTimeout() = runBlocking {
        // No network: both attempts fail at once, finish() must not sit out the 10 s bound.
        val s = GeminiLiveSession("k", GeminiLive.buildSetup(), LiveTransport { _, ev ->
            Thread { ev.onFailure(java.net.UnknownHostException("generativelanguage.googleapis.com"), null) }.start()
            object : LiveSocket {
                override fun send(text: String) = false
                override fun close() {}
            }
        })
        s.start()
        val t0 = System.nanoTime()
        try {
            s.finish()
            fail("expected GeminiLiveException")
        } catch (e: GeminiLiveException) {
            assertEquals(GeminiLiveException.UserKind.NETWORK, e.userKind)
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
    }

    @Test
    fun silentTakeEndsOnActivityEndEchoAndIgnoresInterim() = runBlocking {
        // Live behaviour on silence: an interim from vocabulary bias, then only the
        // ACTIVITY_END echo — no final, no generationComplete.
        val fake = FakeServer { msg ->
            if ("setup" in msg) {
                push("""{"setupComplete":{}}""")
                push(text("interimInputTranscription", "'TraderNet'"))
            } else if (msg.isActivityEnd()) {
                push("""{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_END"}}""")
            }
        }
        val s = session(fake)
        s.start()
        Thread.sleep(100)
        val t0 = System.nanoTime()
        assertEquals("", s.finish())
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)     // no FINISH_TIMEOUT wait
        assertFalse(s.usedPartial)
    }

    @Test
    fun closeBeforeFinishStopsSendingAudio() = runBlocking {
        val fake = server()
        val s = session(fake)
        s.start()
        Thread.sleep(100)
        s.close()
        s.sendPcmBytes(ByteArray(GeminiLive.CHUNK_BYTES))
        assertTrue(fake.kinds().none { it == "audio" })
    }
}
