package com.vela.android.lab.data.market.source.alpaca

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * OkHttp-backed [AlpacaWebSocketFactory] used in the production
 * `VelaLabApplication` DI graph (Phase 2.c onward). Phase 2.b
 * itself never instantiates this class from any wired call site;
 * tests use the in-memory fake instead.
 *
 * The factory rejects any URL that fails
 * [AlpacaStreamEndpoint.requireSafeMarketDataEndpoint] before
 * opening the socket. That guard accepts only the two read-only
 * Market Data WebSocket feeds (test stream and IEX) and rejects
 * every trading host / trading path / SIP / live variant.
 */
class OkHttpAlpacaWebSocketFactory(
    private val client: OkHttpClient = defaultClient(),
) : AlpacaWebSocketFactory {

    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        AlpacaStreamEndpoint.requireSafeMarketDataEndpoint(url)
        val request = Request.Builder().url(url).build()
        val socket: WebSocket = client.newWebSocket(request, BridgeListener(listener))
        return OkHttpHandle(socket)
    }

    private class BridgeListener(
        private val target: AlpacaWebSocketListener,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            target.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            target.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            target.onMessage(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            target.onClosed(code, reason)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            target.onFailure(t, response?.message)
        }
    }

    private class OkHttpHandle(private val socket: WebSocket) : AlpacaWebSocketHandle {
        override fun send(text: String): Boolean = socket.send(text)
        override fun close(code: Int, reason: String) {
            try {
                socket.close(code, reason)
            } catch (_: IllegalStateException) {
                // Already closed.
            }
        }
    }

    companion object {
        /**
         * Defaults tuned for a long-lived streaming socket:
         *  - 10 s TCP connect timeout
         *  - 0 s read timeout (no idle disconnect for a streaming feed)
         *  - retain `pingInterval` at OkHttp's default; the Alpaca
         *    test stream sends heartbeats on its own cadence.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
