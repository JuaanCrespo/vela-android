package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.ExecutionReferencePrice
import com.vela.android.lab.data.market.price.MarketPriceFreshnessPolicy
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.market.price.conservativeAgeMillis
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import kotlin.math.abs
import kotlin.math.min

enum class PaperFinalPriceGateResult {
    ALLOWED,
    PRICE_NOT_FRESH,
    PRICE_DRIFT_EXCEEDED,
    NO_TRUSTED_EXECUTION_PRICE,
}

/** Credential-free diagnostics for the final manual-submit price gate. */
data class PaperFinalPriceEvaluation(
    val result: PaperFinalPriceGateResult,
    val previewPriceUsd: Double?,
    val finalPriceUsd: Double?,
    val finalPriceSource: String?,
    val finalPriceFreshness: String?,
    val finalPriceAgeMillis: Long?,
    val rawFinalPriceAgeMillis: Long?,
    val futureSkewToleranceApplied: Boolean,
    val allowedFutureSkewMillis: Long,
    val driftPercent: Double?,
    val allowedDriftPercent: Double,
    val allowedMaxAgeMillis: Long?,
    val sourceCompatible: Boolean,
) {
    val allowed: Boolean get() = result == PaperFinalPriceGateResult.ALLOWED
}

/**
 * Price-parity policy for the manual Paper submit (Phase 2.v.1, hardened in 2.v.3 and 3.a.1-C).
 *
 * It is pure and local: no network, credential, account, or order dependency.
 *
 * 3.a.1-C rules:
 *  - The final price must be an [ExecutionReferencePrice.Trusted] live quote. Anything else,
 *    including a missing reference, returns [PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE].
 *  - Freshness uses the conservative age: the OLDER of the provider event time and the device
 *    receipt time decides. The earlier policy used the newer timestamp, which is less
 *    conservative. A Room bucket start is never a freshness clock.
 *  - The preview must itself have been priced from a trusted source. A preview priced from a
 *    legacy Room close, a demo bar, or any other non-trusted source can never pass. This closes
 *    the self-comparison loophole, where the same legacy reference is compared with itself and
 *    shows zero drift.
 *
 * A small future-timestamp tolerance ([maxFutureSkewMillis], default 2000 ms) absorbs device clocks
 * that trail the provider clock. It is surfaced in the evaluation and is never a bypass.
 */
class PaperFinalPriceStabilityPolicy(
    private val freshnessPolicy: MarketPriceFreshnessPolicy = MarketPriceFreshnessPolicy(),
    private val maxDriftPercent: Double = DEFAULT_MAX_DRIFT_PERCENT,
    private val maxFinalPriceAgeMillis: Long = DEFAULT_MAX_FINAL_PRICE_AGE_MILLIS,
    private val maxFutureSkewMillis: Long = DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS,
) {
    init {
        require(maxDriftPercent.isFinite() && maxDriftPercent > 0.0) {
            "Final price drift threshold must be positive and finite."
        }
        require(maxFinalPriceAgeMillis in 1_000L..60_000L) {
            "Final price age window must remain short."
        }
        require(maxFutureSkewMillis in 0L..5_000L) {
            "Future price skew tolerance must be small (0..5000 ms)."
        }
    }

    fun evaluate(
        preview: PaperOrderPayloadPreview?,
        finalPrice: ExecutionReferencePrice?,
        nowEpochMillis: Long,
    ): PaperFinalPriceEvaluation {
        val previewPrice = previewPrice(preview)
        val reference = finalPrice as? ExecutionReferencePrice.Trusted
        val latest = reference?.price
        val rawAge = reference?.let {
            maxOf(
                nowEpochMillis - it.eventTimeEpochMillis,
                nowEpochMillis - it.receivedAtEpochMillis,
            )
        }
        val effectiveAge = reference?.let {
            conservativeAgeMillis(
                eventTimeEpochMillis = it.eventTimeEpochMillis,
                receivedAtEpochMillis = it.receivedAtEpochMillis,
                nowEpochMillis = nowEpochMillis,
                maxFutureSkewMillis = maxFutureSkewMillis,
            )
        }
        val toleranceApplied = rawAge != null && rawAge < 0L && effectiveAge != null
        val previewSource = preview?.priceSource?.let(::sourceOrNull)
        val finalSource = reference?.source
        val compatible = previewSource != null &&
            previewSource in TRUSTED_SOURCES &&
            finalSource != null &&
            isSourceCompatible(previewSource, finalSource)
        val sourceFreshnessLimit = finalSource?.let(freshnessPolicy::thresholdFor)
        val allowedAge = sourceFreshnessLimit?.let { min(it, maxFinalPriceAgeMillis) }
        val finalFreshness = when {
            reference == null -> PriceFreshness.MISSING
            effectiveAge != null && allowedAge != null && effectiveAge <= allowedAge ->
                PriceFreshness.FRESH
            else -> PriceFreshness.STALE
        }
        val drift = if (previewPrice != null && latest != null &&
            latest.isFinite() && latest > 0.0
        ) {
            abs(latest - previewPrice) / previewPrice * 100.0
        } else {
            null
        }

        val freshAndValid = preview != null &&
            preview.priceFreshness == PriceFreshness.FRESH.name &&
            previewPrice != null &&
            reference != null &&
            latest != null && latest.isFinite() && latest > 0.0 &&
            reference.symbol.trim().uppercase() == preview.symbol.trim().uppercase() &&
            finalFreshness == PriceFreshness.FRESH &&
            compatible

        val result = when {
            reference == null -> PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE
            !freshAndValid -> PaperFinalPriceGateResult.PRICE_NOT_FRESH
            drift == null || drift > maxDriftPercent ->
                PaperFinalPriceGateResult.PRICE_DRIFT_EXCEEDED
            else -> PaperFinalPriceGateResult.ALLOWED
        }
        return PaperFinalPriceEvaluation(
            result = result,
            previewPriceUsd = previewPrice,
            finalPriceUsd = latest,
            finalPriceSource = finalSource?.name,
            finalPriceFreshness = finalFreshness.name,
            finalPriceAgeMillis = effectiveAge,
            rawFinalPriceAgeMillis = rawAge,
            futureSkewToleranceApplied = toleranceApplied,
            allowedFutureSkewMillis = maxFutureSkewMillis,
            driftPercent = drift,
            allowedDriftPercent = maxDriftPercent,
            allowedMaxAgeMillis = allowedAge,
            sourceCompatible = compatible,
        )
    }

    private fun previewPrice(preview: PaperOrderPayloadPreview?): Double? {
        val notional = preview?.estimatedNotionalUsd ?: return null
        val quantity = preview.quantity
        if (!notional.isFinite() || notional <= 0.0 ||
            !quantity.isFinite() || quantity <= 0.0
        ) {
            return null
        }
        return (notional / quantity).takeIf { it.isFinite() && it > 0.0 }
    }

    private fun sourceOrNull(value: String): MarketPriceSource? =
        MarketPriceSource.entries.firstOrNull { it.name == value }

    private fun isSourceCompatible(
        preview: MarketPriceSource,
        final: MarketPriceSource,
    ): Boolean {
        if (preview == MarketPriceSource.NONE || final == MarketPriceSource.NONE) return false
        return sourceClass(preview) == sourceClass(final) || qualityRank(final) > qualityRank(preview)
    }

    private fun sourceClass(source: MarketPriceSource): PriceSourceClass = when (source) {
        MarketPriceSource.LIVE_QUOTE_MID,
        MarketPriceSource.LIVE_QUOTE_BID_ASK -> PriceSourceClass.QUOTE
        MarketPriceSource.LIVE_BAR_CLOSE,
        MarketPriceSource.ROOM_BAR_CLOSE -> PriceSourceClass.BAR
        MarketPriceSource.NONE -> PriceSourceClass.NONE
    }

    private fun qualityRank(source: MarketPriceSource): Int = when (source) {
        MarketPriceSource.LIVE_QUOTE_MID -> 4
        MarketPriceSource.LIVE_QUOTE_BID_ASK -> 3
        MarketPriceSource.LIVE_BAR_CLOSE -> 2
        MarketPriceSource.ROOM_BAR_CLOSE -> 1
        MarketPriceSource.NONE -> 0
    }

    private enum class PriceSourceClass { QUOTE, BAR, NONE }

    companion object {
        const val DEFAULT_MAX_DRIFT_PERCENT: Double = 0.25
        const val DEFAULT_MAX_FINAL_PRICE_AGE_MILLIS: Long = 10_000L
        const val DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS: Long = 2_000L

        /**
         * Only a two-sided live quote mid may be an execution price (3.a.1-C). A one-sided quote is
         * rejected upstream, so `LIVE_QUOTE_BID_ASK` is never trusted here.
         */
        private val TRUSTED_SOURCES: Set<MarketPriceSource> = setOf(
            MarketPriceSource.LIVE_QUOTE_MID,
        )
    }
}
