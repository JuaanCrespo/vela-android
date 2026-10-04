package com.vela.android.lab.data.market.tick

/**
 * Phase 3.a.1-C provenance of a live market observation.
 *
 * The producer sets this from the endpoint it actually connected to. It is never inferred
 * from a symbol, a timestamp, or a free-text source label.
 *
 * Only [ALPACA_IEX_REAL_TIME] can ever become an execution reference. Every other value,
 * including [UNKNOWN], is execution-ineligible.
 */
enum class MarketDataProvenance {
    /** Read-only Alpaca real-stock IEX stream (`wss://stream.data.alpaca.markets/v2/iex`). */
    ALPACA_IEX_REAL_TIME,

    /** Alpaca FAKEPACA test stream (`wss://stream.data.alpaca.markets/v2/test`). Synthetic. */
    ALPACA_TEST_SYNTHETIC,

    /** Produced locally by a demo control or offline generator. Synthetic. */
    LOCAL_DEMO_SYNTHETIC,

    /** The producer did not declare provenance. Fail closed. */
    UNKNOWN,
}
