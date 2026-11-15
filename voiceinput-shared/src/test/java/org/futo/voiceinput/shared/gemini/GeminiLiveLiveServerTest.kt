package org.futo.voiceinput.shared.gemini

// OmniVerte: manual check against the real Gemini Live server through OkHttp.
// Skipped unless GEMINI_API_KEY is set. Optional GEMINI_LIVE_WAV = path to a
// 16 kHz mono PCM16 WAV with speech; its transcript is printed.
//
//   GEMINI_API_KEY=... ./gradlew :voiceinput-shared:testDebugUnitTest --tests '*LiveServerTest*'

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GeminiLiveLiveServerTest {
    private val key: String? = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }

    private suspend fun stream(session: GeminiLiveSession, pcm: ByteArray): String {
        session.start()
        // Real-time pacing, like the recorder: 100 ms per chunk.
        var off = 0
        while (off < pcm.size) {
            val end = minOf(off + GeminiLive.CHUNK_BYTES, pcm.size)
            session.sendPcmBytes(pcm.copyOfRange(off, end))
            off = end
            Thread.sleep(100)
        }
        return session.finish()
    }

    @Test
    fun silenceGivesEmptyText() = runBlocking {
        assumeTrue("GEMINI_API_KEY not set", key != null)
        val s = GeminiLiveSession(key!!, GeminiLive.buildSetup(listOf("ru", "en")), log = ::println)
        assertEquals("", stream(s, ByteArray(GeminiLive.CHUNK_BYTES * 20)))
    }

    @Test
    fun invalidKeyIsAuthError() = runBlocking {
        assumeTrue("GEMINI_API_KEY not set", key != null)
        val s = GeminiLiveSession("invalid-key", GeminiLive.buildSetup(listOf("ru")), log = ::println)
        try {
            stream(s, ByteArray(GeminiLive.CHUNK_BYTES * 5))
            fail("expected GeminiLiveException")
        } catch (e: GeminiLiveException) {
            println("invalid key → ${e.userKind}: ${e.message}")
            assertEquals(GeminiLiveException.UserKind.AUTH, e.userKind)
        }
    }

    @Test
    fun wavTranscript() = runBlocking {
        val wav = System.getenv("GEMINI_LIVE_WAV")?.let(::File)
        assumeTrue("GEMINI_API_KEY / GEMINI_LIVE_WAV not set", key != null && wav != null && wav.exists())
        val bytes = wav!!.readBytes()
        val pcm = bytes.copyOfRange(44, bytes.size)   // canonical 44-byte header
        val t0 = System.nanoTime()
        val s = GeminiLiveSession(key!!, GeminiLive.buildSetup(listOf("ru", "en")), log = ::println)
        val text = stream(s, pcm)
        println("transcript: $text  (usedPartial=${s.usedPartial}, total ${(System.nanoTime() - t0) / 1_000_000} ms)")
        assertTrue(text.isNotBlank())
    }
}
