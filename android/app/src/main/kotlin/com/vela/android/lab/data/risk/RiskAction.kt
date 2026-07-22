package com.vela.android.lab.data.risk

/**
 * Port of `app/data/risk_manager.py::RiskAction`.
 */
enum class RiskAction(val value: String) {
    ENTRY("ENTRY"),
    EXIT("EXIT"),
}
