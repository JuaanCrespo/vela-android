package com.vela.android.lab.data.paper.status

import java.time.Instant
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/** Pure parser for a single read-only Alpaca Paper order response. */
class PaperOrderStatusJsonParser {

    sealed interface ParseResult<out T> {
        data class Ok<T>(val value: T) : ParseResult<T>
        data class Err(val safeMessage: String) : ParseResult<Nothing>
        data object IdentityMismatch : ParseResult<Nothing>
    }

    fun parse(
        body: String,
        expectedIdentity: PaperOrderLifecycleLookupTarget? = null,
    ): ParseResult<PaperOrderStatusSnapshot> {
        val json = try {
            if (body.isBlank()) return ParseResult.Err("Paper order status response was empty.")
            JSONObject(body)
        } catch (_: JSONException) {
            return ParseResult.Err("Paper order status response was invalid JSON.")
        }

        val orderId = json.optString("id", "")
        if (!isCanonicalOrderId(orderId)) {
            return ParseResult.Err("Paper order status response did not contain a valid id.")
        }

        val clientOrderId = json.optString("client_order_id", "")
        if (clientOrderId.isBlank() || clientOrderId.length > 128) {
            return ParseResult.Err(
                "Paper order status response did not contain a valid client order id.",
            )
        }
        val symbol = json.optString("symbol", "").uppercase(Locale.ROOT)
        if (!symbol.matches(Regex("^[A-Z][A-Z0-9.-]{0,31}$"))) {
            return ParseResult.Err("Paper order status response did not contain a valid symbol.")
        }
        val side = json.optString("side", "").uppercase(Locale.ROOT)
        if (side != "BUY" && side != "SELL") {
            return ParseResult.Err("Paper order status response did not contain a valid side.")
        }
        val quantity = json.requiredPositiveDouble("qty")
            ?: return ParseResult.Err("Paper order status response had an invalid quantity.")
        val orderType = json.optString("type", "").uppercase(Locale.ROOT)
        if (orderType != "MARKET" && orderType != "LIMIT") {
            return ParseResult.Err("Paper order status response did not contain a valid type.")
        }
        val timeInForce = json.optString("time_in_force", "").uppercase(Locale.ROOT)
        if (expectedIdentity != null && (
                orderId != expectedIdentity.orderId ||
                    expectedIdentity.clientOrderId?.let { clientOrderId != it } == true ||
                    symbol != expectedIdentity.symbol ||
                    side != expectedIdentity.side ||
                    quantity != expectedIdentity.quantity ||
                    orderType != expectedIdentity.orderType ||
                    timeInForce != expectedIdentity.timeInForce
            )
        ) {
            return ParseResult.IdentityMismatch
        }
        if (timeInForce != "DAY") {
            return ParseResult.Err(
                "Paper order status response did not contain a valid time-in-force.",
            )
        }

        val rawStatus = json.optString("status", "").lowercase(Locale.ROOT)
        if (!rawStatus.matches(Regex("^[a-z_]{1,40}$"))) {
            return ParseResult.Err("Paper order status response did not contain a valid status.")
        }

        val filledQuantity = json.requiredNonNegativeDouble("filled_qty")
            ?: return ParseResult.Err("Paper order status response had an invalid filled quantity.")
        val filledAveragePrice = json.optionalPositiveDouble("filled_avg_price")
        if (filledAveragePrice is OptionalDouble.Invalid) {
            return ParseResult.Err("Paper order status response had an invalid average price.")
        }
        val filledAt = json.optionalInstant("filled_at")
        if (filledAt is OptionalInstant.Invalid) {
            return ParseResult.Err("Paper order status response had an invalid filled timestamp.")
        }

        return try {
            ParseResult.Ok(
                PaperOrderStatusSnapshot(
                    orderId = orderId,
                    clientOrderId = clientOrderId,
                    symbol = symbol,
                    side = side,
                    quantity = quantity,
                    orderType = orderType,
                    timeInForce = timeInForce,
                    status = PaperOrderLifecycleStatus.fromWireValue(rawStatus),
                    rawStatus = rawStatus,
                    filledQuantity = filledQuantity,
                    filledAveragePriceUsd = (filledAveragePrice as OptionalDouble.Value).value,
                    filledAtIso = (filledAt as OptionalInstant.Value).value,
                ),
            )
        } catch (_: IllegalArgumentException) {
            ParseResult.Err("Paper order status response was internally inconsistent.")
        }
    }

    private fun isCanonicalOrderId(value: String): Boolean = try {
        AlpacaPaperOrderStatusEndpoint.requireCanonicalOrderId(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun JSONObject.requiredNonNegativeDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val parsed = when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return parsed?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun JSONObject.requiredPositiveDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val parsed = when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return parsed?.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun JSONObject.optionalPositiveDouble(key: String): OptionalDouble {
        if (!has(key) || isNull(key)) return OptionalDouble.Value(null)
        val parsed = when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return if (parsed != null && parsed.isFinite() && parsed > 0.0) {
            OptionalDouble.Value(parsed)
        } else {
            OptionalDouble.Invalid
        }
    }

    private fun JSONObject.optionalInstant(key: String): OptionalInstant {
        if (!has(key) || isNull(key)) return OptionalInstant.Value(null)
        val raw = optString(key, "")
        if (raw.isBlank()) return OptionalInstant.Invalid
        return try {
            Instant.parse(raw)
            OptionalInstant.Value(raw)
        } catch (_: RuntimeException) {
            OptionalInstant.Invalid
        }
    }

    private sealed interface OptionalDouble {
        data class Value(val value: Double?) : OptionalDouble
        data object Invalid : OptionalDouble
    }

    private sealed interface OptionalInstant {
        data class Value(val value: String?) : OptionalInstant
        data object Invalid : OptionalInstant
    }
}
