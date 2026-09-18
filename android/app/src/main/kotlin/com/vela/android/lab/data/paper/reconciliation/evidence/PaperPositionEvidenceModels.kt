package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.BrokerPositionSide
import com.vela.android.lab.data.paper.reconciliation.domain.DecimalQuantity
import java.security.MessageDigest

const val POSITION_CAPTURE_PARSER_V1 = "POSITION_CAPTURE_PARSER_V1"
const val POSITION_CAPTURE_SOURCE = "MANUAL_PAPER_ACCOUNT_POSITIONS_GET"
const val POSITION_ENGINE_V1 = "POSITION_ENGINE_2Y2_V1"
const val POSITION_POLICY_V1 = "POSITION_POLICY_2Y3_V1"

enum class CaptureDiagnostic {
    SUCCESS_COMPLETE, NOT_REQUESTED, AUTH_FAILURE, HTTP_FAILURE, NETWORK_FAILURE,
    EMPTY_BODY_INVALID, MALFORMED_JSON, INVALID_ROW, DUPLICATE_SYMBOL, INVALID_SYMBOL,
    INVALID_QTY, NONFINITE_NUMBER, UNEXPECTED_TYPE, INCOMPLETE_RESPONSE,
    SIDE_CONTRADICTION, ACCOUNT_REF_UNKNOWN, INVALID_ACCOUNT, CONFIG_CHANGED,
    CLOCK_REGRESSION, PERSISTENCE_FAILURE,
}

data class CapturedPosition(
    val symbol: String,
    val side: BrokerPositionSide,
    val qtyRawDecimal: String,
    val qtyCanonicalDecimal: String,
)

/** Only allow-listed observed numeric fields. No raw identity, body or auth material. */
data class ObservedPaperAccount(
    val accountRef: String?,
    val cash: String?,
    val equity: String?,
    val buyingPower: String?,
    val portfolioValue: String?,
)

data class EvidenceParseResult<T>(
    val value: T?,
    val diagnostic: CaptureDiagnostic,
    val receivedCount: Int = 0,
    val validatedCount: Int = 0,
) {
    val valid: Boolean get() = diagnostic == CaptureDiagnostic.SUCCESS_COMPLETE && value != null
}

internal fun evidenceDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun validAccountRef(value: String?): Boolean = value?.matches(Regex("paper-v1:[0-9a-f]{64}")) == true
internal fun validEvidenceSymbol(value: String): Boolean = value.matches(Regex("^[A-Z][A-Z0-9.-]{0,31}$"))
internal fun requireCanonicalDecimal(value: String): DecimalQuantity = DecimalQuantity.parse(value).also {
    require(it.toString() == value) { "Noncanonical stored decimal" }
}
