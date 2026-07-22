package com.vela.android.lab.data.paper.preflight

/**
 * Phase 2.m local-only Paper order intent. Pure data; no Android
 * imports. **This class never reaches the network.** It is consumed
 * only by [PaperOrderPreflightEngine], which is a pure function.
 *
 * The intent is a description of a *hypothetical* trade — it can be
 * rendered in the UI, fed into the preflight engine, and discarded.
 * No code path in Phase 2.m converts an intent into an outbound
 * `POST /v2/orders`; doing so would also require a new HTTP method
 * on `AlpacaHttpClient`, which still exposes only `executeGet`.
 */
data class PaperOrderIntent(
    val symbol: String,
    val side: OrderSide,
    val quantity: Double,
    val type: OrderType = OrderType.MARKET,
    val tif: TimeInForce = TimeInForce.DAY,
    /** Only meaningful for LIMIT orders. Future use; ignored for MARKET. */
    val limitPriceUsd: Double? = null,
    val source: IntentSource = IntentSource.MANUAL_DRY_RUN,
    val createdAtEpochMillis: Long,
    val clientDryRunId: String,
)

enum class OrderSide { BUY, SELL }
enum class OrderType { MARKET, LIMIT }
enum class TimeInForce { DAY }

/** Provenance of an intent. Phase 2.m only emits `MANUAL_DRY_RUN`. */
enum class IntentSource { MANUAL_DRY_RUN }
