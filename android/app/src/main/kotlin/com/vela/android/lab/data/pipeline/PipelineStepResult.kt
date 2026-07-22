package com.vela.android.lab.data.pipeline

import com.vela.android.lab.data.market.OneMinuteBar
import com.vela.android.lab.data.market.SymbolFeatures
import com.vela.android.lab.data.market.SymbolSignal

/**
 * Outcome of one call to [OfflineMarketPipelineCoordinator.addUpdate].
 *
 *  - [accepted] is true when the update was routed through the
 *    pipeline. False means the update was rejected at the entry
 *    (e.g., empty/invalid symbol) and only an `invalid_market_update`
 *    journal event was written.
 *  - [bar] / [features] / [signal] are non-null when each respective
 *    stage produced a value during this step.
 *  - [journalEventsRecorded] counts how many journal rows were
 *    written for this update. Tests assert on this directly.
 */
data class PipelineStepResult(
    val symbol: String,
    val accepted: Boolean,
    val bar: OneMinuteBar?,
    val features: SymbolFeatures?,
    val signal: SymbolSignal?,
    val journalEventsRecorded: Int,
)
