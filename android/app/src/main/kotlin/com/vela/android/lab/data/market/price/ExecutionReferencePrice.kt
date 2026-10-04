package com.vela.android.lab.data.market.price

import com.vela.android.lab.data.market.tick.MarketDataProvenance

/**
 * Phase 3.a.1-C execution-authority price. **Pure data and pure functions.**
 *
 * Only [Trusted] carries a numeric price. [Rejected] carries none, so a consumer cannot read a
 * fallback value by accident. The constructor of [Trusted] is private. The only way to obtain one
 * is [Trusted.derive], which applies every policy rule and returns [Rejected] otherwise.
 *
 * Persisted Room bars (`market_bars_1m`) are never an input to this type. No demo, synthetic,
 * test, or unknown-provenance quote can produce a [Trusted] value.
 */
sealed interface ExecutionReferencePrice {
    val symbol: String

    /**
     * A live, real-time IEX quote with both sides valid, that passed every rule in [derive].
     * Instances exist only through [derive]. There is no public constructor, no `copy`, and no
     * settable trust flag.
     */
    class Trusted private constructor(
        override val symbol: String,
        /** Mid of bid and ask, both required. Double, not exact decimal (3.a.1-C doc, §21). */
        val price: Double,
        val source: MarketPriceSource,
        val provenance: MarketDataProvenance,
        /** Provider event time of the quote, epoch millis. */
        val eventTimeEpochMillis: Long,
        /** Device clock at the moment the quote was received and parsed, epoch millis. */
        val receivedAtEpochMillis: Long,
        /** Clock reading at which the trust decision was taken. */
        val evaluatedAtEpochMillis: Long,
        /** Conservative age at evaluation: now minus the OLDER of the two timestamps, clamped to zero. */
        val ageMillis: Long,
    ) : ExecutionReferencePrice {
        init {
            require(provenance == MarketDataProvenance.ALPACA_IEX_REAL_TIME) {
                "Execution reference requires real-time IEX provenance."
            }
            require(source == MarketPriceSource.LIVE_QUOTE_MID) {
                "Execution reference requires a two-sided live quote mid."
            }
            require(price.isFinite() && price > 0.0) {
                "Execution reference price must be finite and positive."
            }
            require(ageMillis in 0L..ExecutionReferencePriceEvaluator.DEFAULT_MAX_AGE_MILLIS) {
                "Execution reference age must be within the freshness limit."
            }
        }

        override fun toString(): String =
            "ExecutionReferencePrice.Trusted(symbol=$symbol, source=${source.name}, " +
                "provenance=${provenance.name}, ageMillis=$ageMillis)"

        companion object {
            /**
             * The single policy gate for execution authority. Rules, in order; the first failure
             * returns [Rejected]:
             *  1. No observation → [ExecutionPriceRejection.NO_LIVE_QUOTE].
             *  2. Provenance is not ALPACA_IEX_REAL_TIME → [ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME].
             *  3. Timestamps invalid at [nowEpochMillis] (see [executionTimeRejection]) → the matching code.
             *  4. Bid or ask missing, non-finite, non-positive, or crossed (bid > ask) → [ExecutionPriceRejection.INVALID_QUOTE].
             *  5. Conservative age above [maxAgeMillis] → [ExecutionPriceRejection.STALE].
             */
            internal fun derive(
                symbol: String,
                observation: LiveQuoteObservation?,
                nowEpochMillis: Long,
                maxAgeMillis: Long,
                maxFutureSkewMillis: Long,
            ): ExecutionReferencePrice {
                if (observation == null) {
                    return Rejected(symbol, ExecutionPriceRejection.NO_LIVE_QUOTE)
                }
                val provenance = observation.provenance
                if (provenance != MarketDataProvenance.ALPACA_IEX_REAL_TIME) {
                    return Rejected(symbol, ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME, provenance)
                }
                executionTimeRejection(
                    eventTimeEpochMillis = observation.eventTimeEpochMillis,
                    receivedAtEpochMillis = observation.receivedAtEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                    maxFutureSkewMillis = maxFutureSkewMillis,
                )?.let { return Rejected(symbol, it, provenance) }
                val bid = observation.bid
                val ask = observation.ask
                val bidValid = bid.isFinite() && bid > 0.0
                val askValid = ask.isFinite() && ask > 0.0
                if (!bidValid || !askValid || bid > ask) {
                    return Rejected(symbol, ExecutionPriceRejection.INVALID_QUOTE, provenance)
                }
                val age = conservativeAgeMillis(
                    eventTimeEpochMillis = observation.eventTimeEpochMillis,
                    receivedAtEpochMillis = observation.receivedAtEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                    maxFutureSkewMillis = maxFutureSkewMillis,
                ) ?: return Rejected(symbol, ExecutionPriceRejection.TIMESTAMP_MISSING, provenance)
                if (age > maxAgeMillis) {
                    return Rejected(symbol, ExecutionPriceRejection.STALE, provenance)
                }
                return Trusted(
                    symbol = symbol,
                    price = (bid + ask) / 2.0,
                    source = MarketPriceSource.LIVE_QUOTE_MID,
                    provenance = provenance,
                    eventTimeEpochMillis = observation.eventTimeEpochMillis,
                    receivedAtEpochMillis = observation.receivedAtEpochMillis,
                    evaluatedAtEpochMillis = nowEpochMillis,
                    ageMillis = age,
                )
            }
        }
    }

    /** No execution authority. [reason] is a deterministic, credential-free code. */
    data class Rejected(
        override val symbol: String,
        val reason: ExecutionPriceRejection,
        val provenance: MarketDataProvenance? = null,
    ) : ExecutionReferencePrice
}

/** Why an execution reference is not trusted. Codes only; no free text. */
enum class ExecutionPriceRejection {
    /** No live quote has been received for the symbol, or it has no receipt time. */
    NO_LIVE_QUOTE,

    /** The quote provenance is not [MarketDataProvenance.ALPACA_IEX_REAL_TIME] (test, demo, or unknown). */
    PROVENANCE_NOT_REAL_TIME,

    /** The provider event time or the device receipt time is missing or not positive. */
    TIMESTAMP_MISSING,

    /** Bid or ask is missing, non-finite, non-positive, or the quote is crossed. */
    INVALID_QUOTE,

    /** The event time or the receipt time is ahead of the device clock by more than the skew tolerance. */
    FUTURE_TIMESTAMP,

    /** The provider event time is after the device receipt time by more than the skew tolerance. */
    EVENT_AFTER_RECEIPT,

    /** The observation is older than the execution freshness limit. */
    STALE,

    /** The quote belongs to a different symbol than the one being authorized. */
    SYMBOL_MISMATCH,

    /** The caller supplied no reference at all. */
    NOT_PROVIDED,

    /** The provider failed while reading the live quote. */
    PROVIDER_FAILED,
}

/** A live quote as held by the in-memory tick buffer. Pure data for the policy. */
data class LiveQuoteObservation(
    val bid: Double,
    val ask: Double,
    val provenance: MarketDataProvenance,
    val eventTimeEpochMillis: Long,
    val receivedAtEpochMillis: Long,
)

/**
 * Temporal validity of an observation at [nowEpochMillis]. Returns `null` when valid, otherwise the
 * rejection code. Rules:
 *  - Either timestamp missing or not positive → [ExecutionPriceRejection.TIMESTAMP_MISSING].
 *  - Event time more than [maxFutureSkewMillis] after now → [ExecutionPriceRejection.FUTURE_TIMESTAMP].
 *  - Receipt time more than [maxFutureSkewMillis] after now → [ExecutionPriceRejection.FUTURE_TIMESTAMP].
 *  - Event time more than [maxFutureSkewMillis] after the receipt time → [ExecutionPriceRejection.EVENT_AFTER_RECEIPT].
 *
 * Network latency makes the receipt later than the event, which is allowed. The skew tolerance covers
 * only clock disagreement between provider and device, and is never a way to accept a future time.
 */
internal fun executionTimeRejection(
    eventTimeEpochMillis: Long,
    receivedAtEpochMillis: Long,
    nowEpochMillis: Long,
    maxFutureSkewMillis: Long,
): ExecutionPriceRejection? {
    if (eventTimeEpochMillis <= 0L || receivedAtEpochMillis <= 0L) {
        return ExecutionPriceRejection.TIMESTAMP_MISSING
    }
    if (eventTimeEpochMillis - nowEpochMillis > maxFutureSkewMillis ||
        receivedAtEpochMillis - nowEpochMillis > maxFutureSkewMillis
    ) {
        return ExecutionPriceRejection.FUTURE_TIMESTAMP
    }
    if (eventTimeEpochMillis - receivedAtEpochMillis > maxFutureSkewMillis) {
        return ExecutionPriceRejection.EVENT_AFTER_RECEIPT
    }
    return null
}

/**
 * Conservative age of an observation at [nowEpochMillis]: the OLDER of the provider event time and the
 * device receipt time decides, so the age is `now - min(eventTime, receivedAt)`, which equals
 * `max(now - eventTime, now - receivedAt)`. Returns `null` when [executionTimeRejection] rejects the
 * timestamps. Otherwise the age is clamped at zero. The clamp only absorbs an age inside the skew
 * tolerance. A timestamp beyond the tolerance is rejected before the clamp can apply.
 *
 * A Room bucket start is never an input to this function.
 */
internal fun conservativeAgeMillis(
    eventTimeEpochMillis: Long,
    receivedAtEpochMillis: Long,
    nowEpochMillis: Long,
    maxFutureSkewMillis: Long,
): Long? {
    if (executionTimeRejection(eventTimeEpochMillis, receivedAtEpochMillis, nowEpochMillis, maxFutureSkewMillis) != null) {
        return null
    }
    return maxOf(nowEpochMillis - eventTimeEpochMillis, nowEpochMillis - receivedAtEpochMillis).coerceAtLeast(0L)
}

/**
 * Entry point for deriving execution authority. Holds the freshness and skew configuration and delegates
 * every decision to [ExecutionReferencePrice.Trusted.derive]. The configuration cannot exceed the
 * live-quote limit or the default skew, so the window cannot be broadened silently.
 */
class ExecutionReferencePriceEvaluator(
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val maxFutureSkewMillis: Long = DEFAULT_MAX_FUTURE_SKEW_MILLIS,
) {
    init {
        require(maxAgeMillis in 1_000L..DEFAULT_MAX_AGE_MILLIS) {
            "Execution freshness may not exceed the live-quote limit."
        }
        require(maxFutureSkewMillis in 0L..DEFAULT_MAX_FUTURE_SKEW_MILLIS) {
            "Future skew tolerance must stay small."
        }
    }

    fun evaluate(
        symbol: String,
        observation: LiveQuoteObservation?,
        nowEpochMillis: Long,
    ): ExecutionReferencePrice = ExecutionReferencePrice.Trusted.derive(
        symbol = symbol,
        observation = observation,
        nowEpochMillis = nowEpochMillis,
        maxAgeMillis = maxAgeMillis,
        maxFutureSkewMillis = maxFutureSkewMillis,
    )

    companion object {
        /** Same limit as the existing live-quote freshness threshold. Not broadened. */
        const val DEFAULT_MAX_AGE_MILLIS: Long = MarketPriceFreshnessPolicy.DEFAULT_LIVE_QUOTE_FRESH_MILLIS

        /** Small tolerance for clock disagreement between the provider and the device. */
        const val DEFAULT_MAX_FUTURE_SKEW_MILLIS: Long = 2_000L
    }
}
