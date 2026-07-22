package com.vela.android.lab.data.market.price

import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.repository.MarketDataRepository
import java.time.Instant

/**
 * Phase 2.o price-snapshot resolver. **Pure local-only.** Builds a
 * [MarketPriceSnapshot] for a symbol by walking a deterministic
 * fallback chain:
 *
 *  1. Latest live quote from the in-memory [MarketTickBuffer]:
 *     mid-price = (bid + ask) / 2 (when both > 0), else the side
 *     that is present.
 *  2. Most-recent Room-persisted bar close from
 *     [MarketDataRepository.recentBars]`(symbol, 1).lastOrNull()`.
 *     Live stream bars already flow through that persistence pipeline.
 *  3. [MarketPriceSnapshot.missing] if nothing above is available.
 *
 * **No network, no credential, no order, no trading-shape method.**
 * The class is constructor-injectable so unit tests can supply a
 * trivial buffer + repo without an Android context.
 */
class MarketPriceSnapshotProvider(
    private val tickBuffer: MarketTickBuffer,
    private val marketDataRepository: MarketDataRepository,
    private val freshnessPolicy: MarketPriceFreshnessPolicy = MarketPriceFreshnessPolicy(),
    private val clock: () -> Instant = { Instant.now() },
) {

    suspend fun snapshotFor(symbol: String): MarketPriceSnapshot {
        val normalized = symbol.trim().uppercase()
        if (normalized.isEmpty()) {
            return MarketPriceSnapshot.missing(symbol, reason = "Empty symbol")
        }
        val nowMillis = clock().toEpochMilli()

        // Tier 1 — live quote (preferred mid-price)
        val perSymbol = tickBuffer.snapshot.value.perSymbol[normalized]
        if (perSymbol != null && perSymbol.lastReceivedAtMillis > 0L) {
            val bid = perSymbol.lastBid.takeIf { it > 0.0 }
            val ask = perSymbol.lastAsk.takeIf { it > 0.0 }
            val source = when {
                bid != null && ask != null -> MarketPriceSource.LIVE_QUOTE_MID
                bid != null || ask != null -> MarketPriceSource.LIVE_QUOTE_BID_ASK
                else -> null
            }
            if (source != null) {
                val price = when (source) {
                    MarketPriceSource.LIVE_QUOTE_MID -> (bid!! + ask!!) / 2.0
                    MarketPriceSource.LIVE_QUOTE_BID_ASK -> ask ?: bid!!
                    else -> bid ?: ask!!
                }
                val age = (nowMillis - perSymbol.lastReceivedAtMillis).coerceAtLeast(0L)
                return MarketPriceSnapshot(
                    symbol = normalized,
                    price = price,
                    bid = bid,
                    ask = ask,
                    marketTimestampMillis = perSymbol.lastQuoteTimestampMillis.takeIf { it > 0L },
                    deviceReceivedAtMillis = perSymbol.lastReceivedAtMillis,
                    ageMillis = age,
                    source = source,
                    freshness = freshnessPolicy.classify(source, age),
                    reason = null,
                )
            }
        }

        // Tier 2 — recent Room bar close (Phase 1.e pipeline persistence)
        val recent = marketDataRepository.recentBars(normalized, 1).lastOrNull()
        if (recent != null) {
            val barTs = recent.bucketStart.toEpochMilli()
            val age = (nowMillis - barTs).coerceAtLeast(0L)
            return MarketPriceSnapshot(
                symbol = normalized,
                price = recent.close,
                bid = null,
                ask = null,
                marketTimestampMillis = barTs,
                deviceReceivedAtMillis = null,
                ageMillis = age,
                source = MarketPriceSource.ROOM_BAR_CLOSE,
                freshness = freshnessPolicy.classify(MarketPriceSource.ROOM_BAR_CLOSE, age),
                reason = if (age > MarketPriceFreshnessPolicy.DEFAULT_ROOM_BAR_FRESH_MILLIS) {
                    "Room bar is older than the freshness threshold"
                } else null,
            )
        }

        return MarketPriceSnapshot.missing(
            normalized,
            reason = "No live quote, no in-memory bar, no Room close for the symbol.",
        )
    }
}
