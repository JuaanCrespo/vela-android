package com.vela.android.lab.data.risk

/**
 * Port of `app/data/risk_manager.py::RiskDecision`. Immutable result
 * value returned from [RiskManager] entry and exit evaluations.
 */
data class RiskDecision(
    val allowed: Boolean,
    val action: RiskAction,
    val symbol: String,
    val requestedSize: Double,
    val reason: String,
    val rule: String? = null,
)
