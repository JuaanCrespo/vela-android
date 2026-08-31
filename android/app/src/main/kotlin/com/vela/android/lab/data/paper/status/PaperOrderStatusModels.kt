package com.vela.android.lab.data.paper.status

import java.time.Instant

/** Alpaca lifecycle values returned by the read-only order lookup. */
enum class PaperOrderLifecycleStatus(val wireValue: String) {
    NEW("new"),
    PARTIALLY_FILLED("partially_filled"),
    FILLED("filled"),
    DONE_FOR_DAY("done_for_day"),
    CANCELED("canceled"),
    EXPIRED("expired"),
    REPLACED("replaced"),
    PENDING_CANCEL("pending_cancel"),
    PENDING_REPLACE("pending_replace"),
    ACCEPTED("accepted"),
    PENDING_NEW("pending_new"),
    ACCEPTED_FOR_BIDDING("accepted_for_bidding"),
    STOPPED("stopped"),
    REJECTED("rejected"),
    SUSPENDED("suspended"),
    CALCULATED("calculated"),
    HELD("held"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String): PaperOrderLifecycleStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/** Credential-free lifecycle snapshot safe to expose to a read-only UI. */
data class PaperOrderStatusSnapshot(
    val orderId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val orderType: String,
    val timeInForce: String,
    val status: PaperOrderLifecycleStatus,
    val rawStatus: String,
    val filledQuantity: Double,
    val filledAveragePriceUsd: Double?,
    val filledAtIso: String?,
) {
    init {
        AlpacaPaperOrderStatusEndpoint.requireCanonicalOrderId(orderId)
        require(clientOrderId.isNotBlank() && clientOrderId.length <= 128) {
            "Paper client order id is invalid."
        }
        require(symbol.matches(Regex("^[A-Z][A-Z0-9.-]{0,31}$"))) {
            "Paper order symbol is invalid."
        }
        require(side == "BUY" || side == "SELL") { "Paper order side is invalid." }
        require(quantity.isFinite() && quantity > 0.0) {
            "Paper order quantity must be positive and finite."
        }
        require(orderType == "MARKET" || orderType == "LIMIT") {
            "Paper order type is invalid."
        }
        require(timeInForce == "DAY") { "Paper order time-in-force is invalid." }
        require(rawStatus.matches(Regex("^[a-z_]{1,40}$"))) {
            "Paper order lifecycle status is invalid."
        }
        require(filledQuantity.isFinite() && filledQuantity >= 0.0) {
            "Filled quantity must be finite and non-negative."
        }
        require(filledQuantity <= quantity) {
            "Filled quantity cannot exceed the order quantity."
        }
        require(
            filledAveragePriceUsd == null ||
                (filledAveragePriceUsd.isFinite() && filledAveragePriceUsd > 0.0),
        ) {
            "Filled average price must be positive and finite when present."
        }
        if (filledAtIso != null) {
            require(runCatching { Instant.parse(filledAtIso) }.isSuccess) {
                "Filled timestamp must be a valid instant when present."
            }
        }
        if (status == PaperOrderLifecycleStatus.FILLED) {
            require(filledQuantity == quantity) {
                "FILLED requires the filled quantity to equal the order quantity."
            }
            require(filledAveragePriceUsd != null) { "FILLED requires an average price." }
            require(filledAtIso != null) { "FILLED requires a filled timestamp." }
        }
    }

    val displayStatus: String
        get() = if (status == PaperOrderLifecycleStatus.UNKNOWN) {
            rawStatus.uppercase()
        } else {
            status.name
        }

    val terminalForNewPreparation: Boolean
        get() = status == PaperOrderLifecycleStatus.FILLED ||
            status == PaperOrderLifecycleStatus.CANCELED ||
            status == PaperOrderLifecycleStatus.EXPIRED ||
            status == PaperOrderLifecycleStatus.REJECTED
}

/** Safe, credential-free transport evidence for one explicit lifecycle GET. */
data class PaperOrderStatusFetchEvidence(
    val httpStatusCode: Int,
    val source: String = SOURCE,
    val method: String = AlpacaPaperOrderStatusEndpoint.METHOD,
) {
    init {
        require(httpStatusCode in 200..299) { "Paper status HTTP success code is invalid." }
        require(source == SOURCE) { "Paper status source is invalid." }
        require(method == AlpacaPaperOrderStatusEndpoint.METHOD) {
            "Paper status evidence must remain GET-only."
        }
    }

    companion object {
        const val SOURCE: String = "ALPACA_PAPER_ORDER_GET"
    }
}
