package com.vela.android.lab.data.market

/**
 * Port of `SignalState` from `app/data/signal_engine.py`.
 *
 * String values match exactly so any future cross-platform journal or
 * payload stays comparable to the Windows project.
 */
enum class SignalState(val value: String) {
    BULLISH("BULLISH"),
    BEARISH("BEARISH"),
    NEUTRAL("NEUTRAL"),
}
