package com.vela.android.lab.data.market.source.alpaca

/**
 * WebSocket abstraction used by [AlpacaTestStreamMarketDataClient].
 *
 * The interface is **read-only by shape** — it supports opening a
 * connection, sending strings, and closing. It does **not** model
 * HTTP requests, REST bodies, or any non-streaming surface. The
 * production implementation is [OkHttpAlpacaWebSocketFactory]; the
 * test source set substitutes an in-memory fake.
 */
fun interface AlpacaWebSocketFactory {
    /**
     * Open a WebSocket against [url]. Implementations must invoke
     * [AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint] before
     * connecting.
     */
    fun open(url: String, listener: AlpacaWebSocketListener): AlpacaWebSocketHandle
}

/** Lifecycle handle for an open WebSocket. */
interface AlpacaWebSocketHandle {
    /** Returns true if the message was enqueued for sending. */
    fun send(text: String): Boolean

    /**
     * Initiate a graceful close. Implementations should not throw
     * if the WebSocket has already been closed or has failed.
     */
    fun close(code: Int = NORMAL_CLOSURE, reason: String = "")

    companion object {
        const val NORMAL_CLOSURE: Int = 1000
    }
}

/**
 * Server → client callbacks. All callbacks run on whatever thread
 * the underlying transport uses; implementations must not assume
 * any specific dispatcher.
 */
interface AlpacaWebSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(throwable: Throwable?, response: String?)
}
