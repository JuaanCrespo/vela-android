package com.vela.android.lab.data.risk

/**
 * Port of `app/data/risk_manager.py::RiskStateSnapshot`.
 *
 * `openSymbols` is normalized to a sorted, de-duplicated list of
 * trimmed-uppercased symbols at construction, matching the Python
 * `tuple(sorted({...}))` shape.
 */
class RiskStateSnapshot(
    openSymbols: Iterable<String> = emptyList(),
    val realizedPnlTotal: Double = 0.0,
) {
    val openSymbols: List<String>

    init {
        this.openSymbols = openSymbols
            .asSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
            .toList()
    }

    val openPositionCount: Int
        get() = openSymbols.size

    fun hasOpenSymbol(symbol: String): Boolean =
        symbol.trim().uppercase() in openSymbols
}
