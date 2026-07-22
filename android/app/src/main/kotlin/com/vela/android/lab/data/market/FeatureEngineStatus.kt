package com.vela.android.lab.data.market

/**
 * Port of `FeatureEngineStatus` from `app/data/feature_engine.py`.
 */
data class FeatureEngineStatus(
    val symbolCount: Int,
    val readySymbols: List<String>,
    val latestFeatures: SymbolFeatures? = null,
)
