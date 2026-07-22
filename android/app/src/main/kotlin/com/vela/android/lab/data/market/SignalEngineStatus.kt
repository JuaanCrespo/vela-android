package com.vela.android.lab.data.market

/**
 * Port of `SignalEngineStatus` from `app/data/signal_engine.py`.
 */
data class SignalEngineStatus(
    val symbolCount: Int,
    val readySymbols: List<String>,
    val latestSignal: SymbolSignal? = null,
)
