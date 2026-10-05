package com.vela.android.lab.data.market.price

/**
 * Phase 3.a.1-D: a persisted Room bar close (`market_bars_1m`) shown for context only.
 *
 * It has no provenance, source, freshness, or trust field, so it cannot be mistaken for a trusted
 * execution reference. No conversion exists from this type to [ExecutionReferencePrice], and no
 * execution, preflight, notional, buying-power, token, gate, or submit path may accept it. Every
 * display of this value must show [LABEL].
 */
data class LegacyDisplayPrice(val price: Double) {
    companion object {
        /** Label required wherever a legacy close is shown. */
        const val LABEL: String = "LEGACY PRICE - NOT FOR EXECUTION"
    }
}
