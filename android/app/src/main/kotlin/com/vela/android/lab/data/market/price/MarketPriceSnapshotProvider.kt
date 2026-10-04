package com.vela.android.lab.data.market.price

import com.vela.android.lab.data.market.tick.MarketTickBuffer
import java.time.Instant

/**
 * Execution-authority price resolver (Phase 3.a.1-C). **Pure local-only.**
 *
 * The only input is the in-memory [MarketTickBuffer]. The constructor takes no
 * `MarketDataRepository`, so a persisted Room bar cannot reach an execution decision. Earlier
 * phases used the latest Room bar close as a fallback. That fallback was removed here.
 *
 * A symbol resolves to [ExecutionReferencePrice.Trusted] only when its latest quote is a
 * real-time IEX quote that passes [ExecutionReferencePriceEvaluator]. Every other case returns
 * [ExecutionReferencePrice.Rejected] with a deterministic reason. There is no fallback.
 *
 * **No network, no credential, no order, no trading-shape method.**
 */
class MarketPriceSnapshotProvider(
    private val tickBuffer: MarketTickBuffer,
    private val evaluator: ExecutionReferencePriceEvaluator = ExecutionReferencePriceEvaluator(),
    private val clock: () -> Instant = { Instant.now() },
) {

    suspend fun executionReferenceFor(symbol: String): ExecutionReferencePrice {
        val normalized = symbol.trim().uppercase()
        val observation = tickBuffer.snapshot.value.perSymbol[normalized]
            ?.takeIf { it.lastReceivedAtMillis > 0L }
            ?.let { stats ->
                LiveQuoteObservation(
                    bid = stats.lastBid,
                    ask = stats.lastAsk,
                    provenance = stats.lastProvenance,
                    eventTimeEpochMillis = stats.lastQuoteTimestampMillis,
                    receivedAtEpochMillis = stats.lastReceivedAtMillis,
                )
            }
        return evaluator.evaluate(normalized, observation, clock().toEpochMilli())
    }
}
