package com.vela.android.lab.data.paper.status

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
    val status: PaperOrderLifecycleStatus,
    val rawStatus: String,
    val filledQuantity: Double,
    val filledAveragePriceUsd: Double?,
    val filledAtIso: String?,
) {
    init {
        AlpacaPaperOrderStatusEndpoint.requireCanonicalOrderId(orderId)
        require(rawStatus.matches(Regex("^[a-z_]{1,40}$"))) {
            "Paper order lifecycle status is invalid."
        }
        require(filledQuantity.isFinite() && filledQuantity >= 0.0) {
            "Filled quantity must be finite and non-negative."
        }
        require(
            filledAveragePriceUsd == null ||
                (filledAveragePriceUsd.isFinite() && filledAveragePriceUsd > 0.0),
        ) {
            "Filled average price must be positive and finite when present."
        }
        if (status == PaperOrderLifecycleStatus.FILLED) {
            require(filledQuantity > 0.0) { "FILLED requires a positive filled quantity." }
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
}
