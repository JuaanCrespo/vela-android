package com.vela.android.lab.data.paper

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure-Kotlin parser for the three Paper Trading read-only JSON
 * responses. Never throws on malformed JSON — returns a typed
 * [ParseResult]. Reuses the [org.json] dependency already on the
 * classpath from [com.vela.android.lab.data.market.source.alpaca.AlpacaStreamMessageParser].
 *
 * No parser method submits orders, mutates state, or touches the
 * network.
 */
class PaperJsonParser {

    sealed interface ParseResult<out T> {
        data class Ok<T>(val value: T) : ParseResult<T>
        data class Err(val message: String) : ParseResult<Nothing>
    }

    fun parseAccount(body: String): ParseResult<PaperAccountSnapshot> = parseObject(body) { json ->
        PaperAccountSnapshot(
            cashUsd = json.optDoubleString("cash"),
            buyingPowerUsd = json.optDoubleString("buying_power"),
            equityUsd = json.optDoubleString("equity"),
            portfolioValueUsd = json.optDoubleString("portfolio_value"),
            tradingBlocked = json.optBoolean("trading_blocked", false),
            accountBlocked = json.optBoolean("account_blocked", false),
            patternDayTrader = json.optBoolean("pattern_day_trader", false),
            currency = json.optString("currency", "USD"),
            status = json.optString("status", ""),
        )
    }

    fun parseClock(body: String): ParseResult<PaperClockSnapshot> = parseObject(body) { json ->
        PaperClockSnapshot(
            isOpen = json.optBoolean("is_open", false),
            nextOpenIso = json.optStringOrNull("next_open"),
            nextCloseIso = json.optStringOrNull("next_close"),
            timestampIso = json.optStringOrNull("timestamp"),
        )
    }

    fun parsePositions(body: String): ParseResult<List<PaperPositionSnapshot>> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ParseResult.Ok(emptyList())
        return try {
            val array = JSONArray(trimmed)
            val out = ArrayList<PaperPositionSnapshot>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out += PaperPositionSnapshot(
                    symbol = obj.optString("symbol", ""),
                    qty = obj.optDoubleString("qty"),
                    marketValueUsd = obj.optDoubleString("market_value"),
                    unrealizedPlUsd = obj.optDoubleString("unrealized_pl"),
                    side = obj.optString("side", ""),
                )
            }
            ParseResult.Ok(out)
        } catch (e: JSONException) {
            ParseResult.Err(e.message ?: "Invalid positions JSON")
        }
    }

    private inline fun <T> parseObject(
        body: String,
        block: (JSONObject) -> T,
    ): ParseResult<T> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ParseResult.Err("Empty body")
        return try {
            ParseResult.Ok(block(JSONObject(trimmed)))
        } catch (e: JSONException) {
            ParseResult.Err(e.message ?: "Invalid JSON")
        }
    }

    /** Alpaca returns numeric fields as JSON strings; this normalizes them. */
    private fun JSONObject.optDoubleString(key: String): Double {
        if (!has(key) || isNull(key)) return 0.0
        return when (val v = opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val s = optString(key, "")
        return s.ifEmpty { null }
    }
}
