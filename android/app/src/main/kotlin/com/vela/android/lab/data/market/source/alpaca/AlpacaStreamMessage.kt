package com.vela.android.lab.data.market.source.alpaca

import java.time.Instant

/**
 * Sealed model of every message type the Alpaca Market Data
 * WebSocket can emit on the test stream. **Read-only.** No member
 * of this hierarchy carries a trading action or account mutation.
 */
sealed interface AlpacaStreamMessage {

    /** `{"T":"success","msg":"connected"}` */
    data object Connected : AlpacaStreamMessage

    /** `{"T":"success","msg":"authenticated"}` */
    data object Authenticated : AlpacaStreamMessage

    /**
     * `{"T":"subscription","trades":[...],"quotes":[...],"bars":[...]}`
     */
    data class Subscription(
        val trades: List<String>,
        val quotes: List<String>,
        val bars: List<String>,
    ) : AlpacaStreamMessage

    /** `{"T":"q","S":"FAKEPACA","bp":...,"ap":...,"t":"..."}` */
    data class Quote(
        val symbol: String,
        val bidPrice: Double,
        val askPrice: Double,
        val timestamp: Instant,
    ) : AlpacaStreamMessage

    /**
     * `{"T":"b","S":"FAKEPACA","o":...,"h":...,"l":...,"c":...,"v":...,"t":"..."}`
     *
     * The test stream emits synthetic bars approximately once per
     * second; FAKEPACA is the only symbol it carries.
     */
    data class Bar(
        val symbol: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val timestamp: Instant,
    ) : AlpacaStreamMessage

    /** `{"T":"error","code":<int>,"msg":"..."}` */
    data class StreamError(
        val code: Int,
        val message: String,
    ) : AlpacaStreamMessage

    /** Anything the parser saw but does not recognise. */
    data class Unknown(val raw: String) : AlpacaStreamMessage
}
