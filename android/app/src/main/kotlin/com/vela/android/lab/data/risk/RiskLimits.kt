package com.vela.android.lab.data.risk

/**
 * Port of `app/data/risk_manager.py::RiskLimits`.
 *
 * Defaults match the Windows project exactly. The `blockedSymbols`
 * constructor argument is normalized (trimmed + uppercased + de-empty)
 * once at construction, mirroring the frozen dataclass `__post_init__`.
 */
class RiskLimits(
    val maxPositionSize: Double = 100.0,
    val maxOpenPositions: Int = 10,
    val maxDailyLoss: Double = 1000.0,
    blockedSymbols: Set<String> = emptySet(),
) {
    val blockedSymbols: Set<String>

    init {
        require(maxPositionSize > 0.0) { "maxPositionSize must be positive." }
        require(maxOpenPositions >= 0) { "maxOpenPositions must be zero or greater." }
        require(maxDailyLoss > 0.0) { "maxDailyLoss must be positive." }
        this.blockedSymbols = blockedSymbols
            .asSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
