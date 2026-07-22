package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.MarketPriceFreshnessPolicy
import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.price.PriceFreshness
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import kotlin.math.abs
import kotlin.math.min

enum class PaperFinalPriceGateResult {
    ALLOWED,
    PRICE_NOT_FRESH,
    PRICE_DRIFT_EXCEEDED,
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
 * Conservative Phase 2.v.1 price-parity policy hardened in Phase 2.v.3.
 *
 * It is pure and local: no network, credential, account, or order dependency. A final
 * price must be fresh, recent, source-compatible, and within the configured drift from
 * the immutable preview price.
 *
 * Phase 2.v.3 adds a small explicit future-timestamp tolerance so that IEX exchange
 * timestamps arriving a few tens or hundreds of milliseconds ahead of the device clock
 * (a common condition on Android emulators whose kernel time trails host UTC even
 * after NTP sync) do not force a fail-closed `PRICE_NOT_FRESH`. Any raw age below
 * `-maxFutureSkewMillis` still blocks; the tolerance is small (default 2000 ms) and
 * is surfaced in the evaluation so the operator can never mistake it for a bypass.
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
        finalPrice: MarketPriceSnapshot?,
        nowEpochMillis: Long,
    ): PaperFinalPriceEvaluation {
        val previewPrice = previewPrice(preview)
        val latest = finalPrice?.price
        val rawAge = rawAgeMillis(finalPrice, nowEpochMillis)
        val toleranceApplied =
            rawAge != null && rawAge < 0L && rawAge >= -maxFutureSkewMillis
        val effectiveAge: Long? = when {
            rawAge == null -> null
            toleranceApplied -> 0L
            else -> rawAge
        }
        val previewSource = preview?.priceSource?.let(::sourceOrNull)
        val finalSource = finalPrice?.source
        val compatible = previewSource != null && finalSource != null &&
            isSourceCompatible(previewSource, finalSource)
        val sourceFreshnessLimit = finalSource?.let(freshnessPolicy::thresholdFor)
        val allowedAge = sourceFreshnessLimit?.let { min(it, maxFinalPriceAgeMillis) }
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
            finalPrice != null &&
            latest != null && latest.isFinite() && latest > 0.0 &&
            finalPrice.symbol.trim().uppercase() == preview.symbol.trim().uppercase() &&
            finalPrice.freshness == PriceFreshness.FRESH &&
            finalSource != MarketPriceSource.NONE &&
            compatible &&
            rawAge != null && rawAge >= -maxFutureSkewMillis &&
            effectiveAge != null && effectiveAge >= 0L &&
            allowedAge != null && effectiveAge <= allowedAge &&
            freshnessPolicy.classify(finalSource, effectiveAge) == PriceFreshness.FRESH

        val result = when {
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
            finalPriceFreshness = finalPrice?.freshness?.name,
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

    private fun rawAgeMillis(
        snapshot: MarketPriceSnapshot?,
        nowEpochMillis: Long,
    ): Long? {
        snapshot ?: return null
        val reference = listOfNotNull(
            snapshot.marketTimestampMillis,
            snapshot.deviceReceivedAtMillis,
        ).maxOrNull()
        return reference?.let { nowEpochMillis - it } ?: snapshot.ageMillis
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
    }
}
