package org.futo.voiceinput.shared.gemini

// OmniVerte: the real WebSocket behind GeminiLiveSession.

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class OkHttpLiveTransport(
    private val client: OkHttpClient = sharedClient,
) : LiveTransport {

    override fun open(apiKey: String, events: LiveSocketEvents): LiveSocket {
        // The key rides in the query string, as the Live API expects. Never log the URL.
        val request = Request.Builder()
            .url("${GeminiLive.LIVE_URL}?key=$apiKey")
            .header("User-Agent", "OmniVerte Keyboard (Android)")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = events.onText(text)

            // The Live API sends its JSON in binary frames too.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.onText(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.onServerClose(code, reason)
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A handshake answered with HTTP (403, 404, …) arrives here with a response.
                events.onFailure(t, response?.code)
                response?.close()
            }
        })

        return object : LiveSocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close() {
                if (!ws.close(1000, null)) ws.cancel()
            }
        }
    }

    companion object {
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(GeminiLiveSession.CONNECT_ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)      // a live socket idles between frames
                .writeTimeout(GeminiLiveSession.CONNECT_ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
